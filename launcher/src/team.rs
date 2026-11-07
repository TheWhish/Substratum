use std::error::Error;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use ed25519_dalek::{Signer, SigningKey};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
pub use substratum_protocol::Profile;
use substratum_protocol::{
    CONTROL_PATH, Presence, RELAY_CHUNK, RELAY_PATH, Refusal, Relay, Team, ToClient, ToServer,
    hello_message, hex,
};
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::mpsc::{UnboundedReceiver, UnboundedSender, unbounded_channel};
use tokio::task::JoinHandle;
use tokio_tungstenite::tungstenite::Message;

const BACKEND: &str = "wss://substratum.thewhish.xyz";
const RETRY_FIRST: Duration = Duration::from_secs(1);
const RETRY_MAX: Duration = Duration::from_secs(30);
const IDLE: Duration = Duration::from_secs(60);
const LINE_LIMIT: u64 = 64 * 1024;

type Failure = Box<dyn Error + Send + Sync>;

pub enum Command {
    Join(String),
    Leave,
    Kick(String),
    Profile(Profile),
}

pub struct Update {
    pub online: bool,
    pub me: String,
    pub team: Option<Team>,
    pub refused: Option<Refusal>,
}

#[derive(Clone)]
pub struct Handle {
    pub link: u16,
    commands: UnboundedSender<Event>,
}

impl Handle {
    pub fn send(&self, command: Command) {
        let _ = self.commands.send(Event::Ui(command));
    }
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
enum FromGame {
    Presence { presence: Presence },
    Start,
    Opened { port: u16 },
    Ended,
}

#[derive(Serialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
enum ToGame<'a> {
    State {
        me: &'a str,
        team: Option<&'a Team>,
        door: Option<u16>,
    },
    Refused {
        reason: Refusal,
    },
}

enum Event {
    Ui(Command),
    Connected(UnboundedSender<ToServer>),
    Server(ToClient),
    Disconnected,
    GameConnected(UnboundedSender<String>),
    Game(FromGame),
    GameGone,
}

pub fn start(
    key: SigningKey,
    profile: Profile,
    notify: impl Fn(Update) + Send + 'static,
) -> std::io::Result<Handle> {
    let link = std::net::TcpListener::bind(("127.0.0.1", 0))?;
    link.set_nonblocking(true)?;
    let port = link.local_addr()?.port();
    let url = std::env::var("SUBSTRATUM_BACKEND").unwrap_or_else(|_| BACKEND.to_owned());
    let (events, inbox) = unbounded_channel();
    let commands = events.clone();
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    std::thread::Builder::new()
        .name("team".into())
        .spawn(move || {
            runtime.block_on(async move {
                let profile = Arc::new(Mutex::new(profile));
                match TcpListener::from_std(link) {
                    Ok(link) => {
                        tokio::spawn(serve_game(link, events.clone()));
                    }
                    Err(error) => eprintln!("game link is unavailable: {error}"),
                }
                tokio::spawn(control(url.clone(), key, profile.clone(), events.clone()));
                Core::new(url, profile, Box::new(notify)).run(inbox).await;
            })
        })?;
    Ok(Handle {
        link: port,
        commands,
    })
}

struct Core {
    url: String,
    profile: Arc<Mutex<Profile>>,
    notify: Box<dyn Fn(Update) + Send>,
    server: Option<UnboundedSender<ToServer>>,
    game: Option<UnboundedSender<String>>,
    me: String,
    token: String,
    team: Option<Team>,
    presence: Presence,
    world: Option<u16>,
    door: Option<(u16, JoinHandle<()>)>,
}

impl Core {
    fn new(url: String, profile: Arc<Mutex<Profile>>, notify: Box<dyn Fn(Update) + Send>) -> Core {
        Core {
            url,
            profile,
            notify,
            server: None,
            game: None,
            me: String::new(),
            token: String::new(),
            team: None,
            presence: Presence::Away,
            world: None,
            door: None,
        }
    }

    async fn run(mut self, mut inbox: UnboundedReceiver<Event>) {
        while let Some(event) = inbox.recv().await {
            self.handle(event);
        }
    }

    fn handle(&mut self, event: Event) {
        match event {
            Event::Ui(Command::Profile(profile)) => {
                *self.profile.lock().unwrap_or_else(PoisonError::into_inner) = profile.clone();
                self.tell(ToServer::Profile(profile));
            }
            Event::Ui(Command::Join(code)) => self.tell(ToServer::Join { code }),
            Event::Ui(Command::Leave) => self.tell(ToServer::Leave),
            Event::Ui(Command::Kick(uuid)) => self.tell(ToServer::Kick { uuid }),
            Event::Connected(server) => {
                let _ = server.send(ToServer::Presence {
                    presence: self.presence,
                });
                self.server = Some(server);
            }
            Event::Disconnected => {
                self.server = None;
                self.team = None;
                self.publish(None);
            }
            Event::Server(ToClient::Challenge { .. }) => {}
            Event::Server(ToClient::Welcome { uuid, token }) => {
                self.me = uuid;
                self.token = token;
            }
            Event::Server(ToClient::Team { team }) => {
                self.team = Some(team);
                self.publish(None);
            }
            Event::Server(ToClient::Refused { reason }) => {
                self.send_game(&ToGame::Refused { reason });
                self.publish(Some(reason));
            }
            Event::Server(ToClient::Incoming { stream }) => self.accept(stream),
            Event::GameConnected(game) => {
                self.game = Some(game);
                self.publish(None);
            }
            Event::Game(message) => self.on_game(message),
            Event::GameGone => {
                self.game = None;
                self.world = None;
                self.presence = Presence::Away;
                self.tell(ToServer::Presence {
                    presence: Presence::Away,
                });
                if self.hosting_session() {
                    self.tell(ToServer::Ended);
                }
            }
        }
    }

    fn on_game(&mut self, message: FromGame) {
        match message {
            FromGame::Presence { presence } => {
                self.presence = presence;
                self.tell(ToServer::Presence { presence });
            }
            FromGame::Start => self.tell(ToServer::Start),
            FromGame::Opened { port } => {
                self.world = Some(port);
                self.tell(ToServer::Opened);
            }
            FromGame::Ended => {
                self.world = None;
                self.tell(ToServer::Ended);
            }
        }
    }

    fn tell(&self, message: ToServer) {
        if let Some(server) = &self.server {
            let _ = server.send(message);
        }
    }

    fn hosting_session(&self) -> bool {
        self.team
            .as_ref()
            .is_some_and(|team| team.host == self.me && team.session.is_some())
    }

    fn publish(&mut self, refused: Option<Refusal>) {
        self.reconcile_door();
        (self.notify)(Update {
            online: self.server.is_some(),
            me: self.me.clone(),
            team: self.team.clone(),
            refused,
        });
        let state = ToGame::State {
            me: &self.me,
            team: self.team.as_ref(),
            door: self.door.as_ref().map(|(port, _)| *port),
        };
        self.send_game(&state);
    }

    fn send_game(&self, message: &ToGame) {
        if let Some(game) = &self.game {
            let line = serde_json::to_string(message).expect("game messages always serialize");
            let _ = game.send(line);
        }
    }

    fn reconcile_door(&mut self) {
        let wanted = self.team.as_ref().is_some_and(|team| {
            team.host != self.me
                && team.takes_part(&self.me)
                && team.session.as_ref().is_some_and(|session| session.open)
        });
        match (wanted, &self.door) {
            (true, None) => match std::net::TcpListener::bind(("127.0.0.1", 0))
                .and_then(|listener| {
                    listener.set_nonblocking(true)?;
                    TcpListener::from_std(listener)
                }) {
                Ok(listener) => {
                    let port = listener.local_addr().map(|address| address.port()).unwrap_or(0);
                    let task = tokio::spawn(serve_door(listener, self.url.clone(), self.token.clone()));
                    self.door = Some((port, task));
                }
                Err(error) => eprintln!("failed to open the door to the host: {error}"),
            },
            (false, Some(_)) => {
                if let Some((_, task)) = self.door.take() {
                    task.abort();
                }
            }
            _ => {}
        }
    }

    fn accept(&self, stream: u64) {
        let Some(port) = self.world else {
            return;
        };
        let (url, token) = (self.url.clone(), self.token.clone());
        tokio::spawn(async move {
            let result = async {
                let local = TcpStream::connect(("127.0.0.1", port)).await?;
                relay(&url, Relay::Accept { token, stream }, local).await
            };
            if let Err(error) = result.await {
                eprintln!("relay to the local world failed: {error}");
            }
        });
    }
}

async fn control(
    url: String,
    key: SigningKey,
    profile: Arc<Mutex<Profile>>,
    events: UnboundedSender<Event>,
) {
    let mut delay = RETRY_FIRST;
    loop {
        let began = Instant::now();
        if let Err(error) = converse(&url, &key, &profile, &events).await {
            eprintln!("team service connection failed: {error}");
        }
        if began.elapsed() > RETRY_MAX {
            delay = RETRY_FIRST;
        }
        if events.send(Event::Disconnected).is_err() {
            return;
        }
        tokio::time::sleep(delay).await;
        delay = (delay * 2).min(RETRY_MAX);
    }
}

async fn converse(
    url: &str,
    key: &SigningKey,
    profile: &Mutex<Profile>,
    events: &UnboundedSender<Event>,
) -> Result<(), Failure> {
    let (socket, _) =
        tokio_tungstenite::connect_async_with_config(format!("{url}{CONTROL_PATH}"), None, true)
            .await?;
    let (mut sink, mut stream) = socket.split();
    let nonce = match read(&mut stream).await? {
        Some(ToClient::Challenge { nonce }) => nonce,
        _ => return Err("the team service did not send a challenge".into()),
    };
    let hello = ToServer::Hello {
        key: hex(&key.verifying_key().to_bytes()),
        signature: hex(&key.sign(&hello_message(&nonce)).to_bytes()),
        profile: profile.lock().unwrap_or_else(PoisonError::into_inner).clone(),
    };
    sink.send(Message::text(serde_json::to_string(&hello)?)).await?;
    let (outbox, mut inbox) = unbounded_channel();
    events
        .send(Event::Connected(outbox))
        .map_err(|_| "the launcher stopped")?;
    loop {
        tokio::select! {
            message = inbox.recv() => {
                let Some(message) = message else { return Ok(()) };
                sink.send(Message::text(serde_json::to_string(&message)?)).await?;
            }
            message = read(&mut stream) => match message? {
                Some(message) => events
                    .send(Event::Server(message))
                    .map_err(|_| "the launcher stopped")?,
                None => return Ok(()),
            },
        }
    }
}

async fn read<S>(stream: &mut S) -> Result<Option<ToClient>, Failure>
where
    S: futures_util::Stream<Item = Result<Message, tokio_tungstenite::tungstenite::Error>> + Unpin,
{
    while let Some(frame) = tokio::time::timeout(IDLE, stream.next()).await? {
        match frame? {
            Message::Text(text) => return Ok(Some(serde_json::from_str(text.as_str())?)),
            Message::Close(_) => return Ok(None),
            _ => {}
        }
    }
    Ok(None)
}

async fn serve_game(listener: TcpListener, events: UnboundedSender<Event>) {
    loop {
        let socket = match listener.accept().await {
            Ok((socket, _)) => socket,
            Err(error) => {
                eprintln!("game link accept failed: {error}");
                continue;
            }
        };
        let (reader, mut writer) = socket.into_split();
        let (outbox, mut inbox) = unbounded_channel::<String>();
        if events.send(Event::GameConnected(outbox)).is_err() {
            return;
        }
        let writing = async {
            while let Some(line) = inbox.recv().await {
                if writer.write_all(format!("{line}\n").as_bytes()).await.is_err() {
                    return;
                }
            }
        };
        let reading = async {
            let mut reader = BufReader::new(reader);
            let mut line = String::new();
            loop {
                line.clear();
                match (&mut reader).take(LINE_LIMIT).read_line(&mut line).await {
                    Ok(0) | Err(_) => return,
                    Ok(_) if !line.ends_with('\n') => return,
                    Ok(_) => match serde_json::from_str::<FromGame>(&line) {
                        Ok(message) => {
                            if events.send(Event::Game(message)).is_err() {
                                return;
                            }
                        }
                        Err(error) => eprintln!("unreadable message from the game: {error}"),
                    },
                }
            }
        };
        tokio::select! {
            () = writing => {}
            () = reading => {}
        }
        if events.send(Event::GameGone).is_err() {
            return;
        }
    }
}

async fn serve_door(listener: TcpListener, url: String, token: String) {
    loop {
        let Ok((local, _)) = listener.accept().await else {
            continue;
        };
        let (url, token) = (url.clone(), token.clone());
        tokio::spawn(async move {
            if let Err(error) = relay(&url, Relay::Dial { token }, local).await {
                eprintln!("relay to the host failed: {error}");
            }
        });
    }
}

async fn relay(url: &str, hello: Relay, local: TcpStream) -> Result<(), Failure> {
    local.set_nodelay(true)?;
    let (socket, _) =
        tokio_tungstenite::connect_async_with_config(format!("{url}{RELAY_PATH}"), None, true)
            .await?;
    let (mut sink, mut stream) = socket.split();
    sink.send(Message::text(serde_json::to_string(&hello)?)).await?;
    let (mut from_game, mut to_game) = local.into_split();
    let outgoing = async {
        let mut buffer = vec![0; RELAY_CHUNK];
        loop {
            let read = from_game.read(&mut buffer).await?;
            if read == 0 {
                sink.close().await?;
                return Ok::<(), Failure>(());
            }
            sink.send(Message::binary(buffer[..read].to_vec())).await?;
        }
    };
    let incoming = async {
        while let Some(frame) = stream.next().await {
            match frame? {
                Message::Binary(data) => to_game.write_all(&data).await?,
                Message::Close(_) => break,
                _ => {}
            }
        }
        to_game.shutdown().await?;
        Ok::<(), Failure>(())
    };
    tokio::select! {
        result = outgoing => result,
        result = incoming => result,
    }
}
