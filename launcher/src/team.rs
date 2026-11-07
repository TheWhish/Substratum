use std::error::Error;
use std::net::SocketAddr;
use std::sync::{Arc, Mutex, PoisonError};
use std::time::{Duration, Instant};

use ed25519_dalek::{Signer, SigningKey};
use futures_util::{SinkExt, StreamExt};
use quinn::{Connection, Endpoint};
use serde::{Deserialize, Serialize};
pub use substratum_protocol::Profile;
use substratum_protocol::{
    BEACON_PORT, CONTROL_PATH, Presence, RELAY_CHUNK, RELAY_PATH, Refusal, Relay, Team, ToClient,
    ToServer, hello_message, hex, unhex,
};
use tokio::io::{AsyncBufRead, AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio::sync::mpsc::{UnboundedReceiver, UnboundedSender, unbounded_channel};
use tokio::sync::watch;
use tokio::task::JoinHandle;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::tungstenite::http::Uri;

use crate::direct::{self, Direct};

const BACKEND: &str = "wss://substratum.thewhish.xyz";
const RETRY_FIRST: Duration = Duration::from_secs(1);
const RETRY_MAX: Duration = Duration::from_secs(30);
const IDLE: Duration = Duration::from_secs(60);
const LINE_LIMIT: u64 = 64 * 1024;
const BEACON_EVERY: Duration = Duration::from_secs(15);
const ROUTE_WAIT: Duration = Duration::from_secs(5);
const GREETING_WAIT: Duration = Duration::from_secs(5);

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
    pub direct: Option<bool>,
}

#[derive(Clone)]
pub struct Handle {
    pub link: GameLink,
    commands: UnboundedSender<Event>,
}

#[derive(Clone)]
pub struct GameLink {
    pub port: u16,
    pub secret: String,
}

impl Handle {
    pub fn send(&self, command: Command) {
        let _ = self.commands.send(Event::Ui(command));
    }
}

#[derive(Deserialize)]
#[serde(tag = "type", rename_all = "kebab-case")]
enum FromGame {
    Hello { key: String },
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
    Peer(Connection, String),
    Routed,
}

#[derive(Clone)]
enum Route {
    Pending,
    Direct(Connection),
    Relay,
}

struct Door {
    port: u16,
    task: JoinHandle<()>,
    route: Arc<watch::Sender<Route>>,
}

pub fn start(
    key: SigningKey,
    profile: Profile,
    notify: impl Fn(Update) + Send + 'static,
) -> std::io::Result<Handle> {
    let link = std::net::TcpListener::bind(("127.0.0.1", 0))?;
    link.set_nonblocking(true)?;
    let port = link.local_addr()?.port();
    let mut bytes = [0; 32];
    getrandom::fill(&mut bytes).map_err(std::io::Error::other)?;
    let secret = hex(&bytes);
    let expected = secret.clone();
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
                        tokio::spawn(serve_game(link, expected, events.clone()));
                    }
                    Err(error) => eprintln!("game link is unavailable: {error}"),
                }
                let direct = match direct::open(&key) {
                    Ok(direct) => {
                        tokio::spawn(accept_direct(direct.endpoint.clone(), events.clone()));
                        Some(direct)
                    }
                    Err(error) => {
                        eprintln!("direct connections are unavailable: {error}");
                        None
                    }
                };
                tokio::spawn(control(url.clone(), key, profile.clone(), events.clone()));
                Core::new(url, profile, Box::new(notify), events, direct)
                    .run(inbox)
                    .await;
            })
        })?;
    Ok(Handle {
        link: GameLink { port, secret },
        commands,
    })
}

struct Core {
    url: String,
    profile: Arc<Mutex<Profile>>,
    notify: Box<dyn Fn(Update) + Send>,
    events: UnboundedSender<Event>,
    direct: Option<Direct>,
    beacon: Option<JoinHandle<()>>,
    peers: Vec<Connection>,
    server: Option<UnboundedSender<ToServer>>,
    game: Option<UnboundedSender<String>>,
    me: String,
    token: String,
    team: Option<Team>,
    presence: Presence,
    world: Option<u16>,
    door: Option<Door>,
}

impl Core {
    fn new(
        url: String,
        profile: Arc<Mutex<Profile>>,
        notify: Box<dyn Fn(Update) + Send>,
        events: UnboundedSender<Event>,
        direct: Option<Direct>,
    ) -> Core {
        Core {
            url,
            profile,
            notify,
            events,
            direct,
            beacon: None,
            peers: Vec::new(),
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
                if let Some(beacon) = self.beacon.take() {
                    beacon.abort();
                }
                self.server = None;
                self.team = None;
                self.publish(None);
            }
            Event::Server(ToClient::Challenge { .. }) => {}
            Event::Server(ToClient::Welcome {
                uuid,
                token,
                beacon,
            }) => {
                self.me = uuid;
                self.token = token;
                self.signal(&beacon);
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
            Event::Server(ToClient::Punch { peer, address }) => self.punch(&peer, address),
            Event::Peer(connection, peer) => self.admit(connection, &peer),
            Event::Routed => self.publish(None),
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
            FromGame::Hello { .. } => {}
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
        self.reconcile_peers();
        let direct = self.door.as_ref().and_then(|door| match &*door.route.borrow() {
            Route::Pending => None,
            Route::Direct(_) => Some(true),
            Route::Relay => Some(false),
        });
        (self.notify)(Update {
            online: self.server.is_some(),
            me: self.me.clone(),
            team: self.team.clone(),
            refused,
            direct,
        });
        let state = ToGame::State {
            me: &self.me,
            team: self.team.as_ref(),
            door: self.door.as_ref().map(|door| door.port),
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
                    let initial = match self.direct {
                        Some(_) => Route::Pending,
                        None => Route::Relay,
                    };
                    let route = Arc::new(watch::channel(initial).0);
                    let receiver = route.subscribe();
                    let door = serve_door(listener, self.url.clone(), self.token.clone(), receiver);
                    let task = tokio::spawn(door);
                    if self.direct.is_some() {
                        tokio::spawn(settle(route.clone(), self.events.clone()));
                        self.tell(ToServer::Punch);
                    }
                    self.door = Some(Door { port, task, route });
                }
                Err(error) => eprintln!("failed to open the door to the host: {error}"),
            },
            (false, Some(_)) => {
                if let Some(door) = self.door.take() {
                    door.task.abort();
                }
            }
            _ => {}
        }
    }

    fn reconcile_peers(&mut self) {
        let hosting = self.world.is_some()
            && self.team.as_ref().is_some_and(|team| {
                team.host == self.me && team.session.as_ref().is_some_and(|session| session.open)
            });
        if !hosting {
            for peer in self.peers.drain(..) {
                peer.close(0u32.into(), b"the game is over");
            }
        }
    }

    fn signal(&mut self, beacon: &str) {
        if let Some(previous) = self.beacon.take() {
            previous.abort();
        }
        let (Some(direct), Some(beacon)) = (&self.direct, unhex(beacon)) else {
            return;
        };
        let announcing = announce(direct.socket.clone(), self.url.clone(), beacon);
        self.beacon = Some(tokio::spawn(announcing));
    }

    fn punch(&self, peer: &str, address: SocketAddr) {
        let (Some(direct), Some(team)) = (&self.direct, &self.team) else {
            return;
        };
        if team.host == self.me && self.hosting_session() && team.takes_part(peer) {
            tokio::spawn(direct::punch(direct.socket.clone(), address));
            return;
        }
        let Some(door) = &self.door else {
            return;
        };
        if team.host != peer {
            return;
        }
        tokio::spawn(direct::punch(direct.socket.clone(), address));
        tokio::spawn(reach(
            direct.endpoint.clone(),
            address,
            peer.to_owned(),
            door.route.clone(),
            self.events.clone(),
        ));
    }

    fn admit(&mut self, connection: Connection, peer: &str) {
        let world = self.world.filter(|_| {
            peer != self.me
                && self.team.as_ref().is_some_and(|team| {
                    team.host == self.me
                        && team.takes_part(peer)
                        && team.session.as_ref().is_some_and(|session| session.open)
                })
        });
        let Some(world) = world else {
            connection.close(0u32.into(), b"not a participant");
            return;
        };
        self.peers.push(connection.clone());
        tokio::spawn(direct::serve(connection, world));
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

async fn serve_game(listener: TcpListener, secret: String, events: UnboundedSender<Event>) {
    loop {
        let socket = match listener.accept().await {
            Ok((socket, _)) => socket,
            Err(error) => {
                eprintln!("game link accept failed: {error}");
                continue;
            }
        };
        let (reader, mut writer) = socket.into_split();
        let mut reader = BufReader::new(reader);
        let mut line = String::new();
        let greeting = tokio::time::timeout(GREETING_WAIT, read_line(&mut reader, &mut line)).await;
        let greeted = matches!(greeting, Ok(Ok(true)))
            && matches!(serde_json::from_str(&line), Ok(FromGame::Hello { key }) if key == secret);
        if !greeted {
            eprintln!("refused a game link without the launcher key");
            continue;
        }
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
            while let Ok(true) = read_line(&mut reader, &mut line).await {
                match serde_json::from_str::<FromGame>(&line) {
                    Ok(message) => {
                        if events.send(Event::Game(message)).is_err() {
                            return;
                        }
                    }
                    Err(error) => eprintln!("unreadable message from the game: {error}"),
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

async fn read_line<R: AsyncBufRead + Unpin>(reader: &mut R, line: &mut String) -> std::io::Result<bool> {
    line.clear();
    let read = (&mut *reader).take(LINE_LIMIT).read_line(line).await?;
    Ok(read > 0 && line.ends_with('\n'))
}

async fn serve_door(
    listener: TcpListener,
    url: String,
    token: String,
    route: watch::Receiver<Route>,
) {
    loop {
        let Ok((local, _)) = listener.accept().await else {
            continue;
        };
        let (url, token, mut route) = (url.clone(), token.clone(), route.clone());
        tokio::spawn(async move {
            let chosen = route
                .wait_for(|route| !matches!(route, Route::Pending))
                .await
                .map(|route| route.clone());
            if let Ok(Route::Direct(connection)) = chosen
                && let Ok((send, receive)) = connection.open_bi().await
            {
                if let Err(error) = direct::bridge(send, receive, local).await {
                    eprintln!("direct stream to the host failed: {error}");
                }
                return;
            }
            if let Err(error) = relay(&url, Relay::Dial { token }, local).await {
                eprintln!("relay to the host failed: {error}");
            }
        });
    }
}

async fn settle(route: Arc<watch::Sender<Route>>, events: UnboundedSender<Event>) {
    tokio::time::sleep(ROUTE_WAIT).await;
    let fell_back = route.send_if_modified(|route| {
        let pending = matches!(route, Route::Pending);
        if pending {
            *route = Route::Relay;
        }
        pending
    });
    if fell_back {
        let _ = events.send(Event::Routed);
    }
}

async fn reach(
    endpoint: Endpoint,
    address: SocketAddr,
    host: String,
    route: Arc<watch::Sender<Route>>,
    events: UnboundedSender<Event>,
) {
    let reached = direct::connect(&endpoint, address, &host).await;
    route.send_replace(reached.map_or(Route::Relay, Route::Direct));
    let _ = events.send(Event::Routed);
}

async fn accept_direct(endpoint: Endpoint, events: UnboundedSender<Event>) {
    while let Some(incoming) = endpoint.accept().await {
        let events = events.clone();
        tokio::spawn(async move {
            let connection = match incoming.await {
                Ok(connection) => connection,
                Err(error) => {
                    eprintln!("incoming direct connection failed: {error}");
                    return;
                }
            };
            match direct::peer(&connection) {
                Some(peer) => {
                    let _ = events.send(Event::Peer(connection, peer));
                }
                None => connection.close(0u32.into(), b"unknown peer"),
            }
        });
    }
}

async fn announce(socket: Arc<UdpSocket>, url: String, beacon: Vec<u8>) {
    loop {
        if let Err(error) = beam(&socket, &url, &beacon).await {
            eprintln!("team service beacon failed: {error}");
        }
        tokio::time::sleep(BEACON_EVERY).await;
    }
}

async fn beam(socket: &UdpSocket, url: &str, beacon: &[u8]) -> Result<(), Failure> {
    let uri: Uri = url.parse()?;
    let host = uri.host().ok_or("the team service address has no host")?;
    let service = tokio::net::lookup_host((host, BEACON_PORT))
        .await?
        .find(SocketAddr::is_ipv4)
        .ok_or("the team service has no IPv4 address")?;
    socket.send_to(beacon, service).await?;
    Ok(())
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

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;
    use tokio::io::{AsyncBufReadExt, BufReader, Lines};
    use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};

    struct Game {
        lines: Lines<BufReader<OwnedReadHalf>>,
        writer: OwnedWriteHalf,
    }

    impl Game {
        async fn attach(link: &GameLink) -> Game {
            let (reader, writer) = TcpStream::connect(("127.0.0.1", link.port)).await.unwrap().into_split();
            let mut game = Game {
                lines: BufReader::new(reader).lines(),
                writer,
            };
            game.say(&format!(r#"{{"type":"hello","key":"{}"}}"#, link.secret)).await;
            game
        }

        async fn say(&mut self, line: &str) {
            self.writer.write_all(format!("{line}\n").as_bytes()).await.unwrap();
        }

        async fn until(&mut self, wanted: impl Fn(&Value) -> bool) -> Value {
            loop {
                let line = self.lines.next_line().await.unwrap().expect("the launcher closed the link");
                let state: Value = serde_json::from_str(&line).unwrap();
                if wanted(&state) {
                    return state;
                }
            }
        }
    }

    fn launcher(seed: u8, nick: &str) -> (Handle, UnboundedReceiver<Update>) {
        let (updates, inbox) = unbounded_channel();
        let profile = Profile {
            nick: nick.into(),
            skin: "red".into(),
            build: "1".into(),
        };
        let handle = start(SigningKey::from_bytes(&[seed; 32]), profile, move |update| {
            let _ = updates.send(update);
        });
        (handle.unwrap(), inbox)
    }

    #[tokio::test]
    #[ignore = "needs a running team service in SUBSTRATUM_BACKEND, set SUBSTRATUM_ROUTE=relay when its beacon port is closed"]
    async fn a_guest_reaches_the_host_world() {
        let world = TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let world_port = world.local_addr().unwrap().port();
        tokio::spawn(async move {
            while let Ok((socket, _)) = world.accept().await {
                tokio::spawn(async move {
                    let (mut reader, mut writer) = socket.into_split();
                    let _ = tokio::io::copy(&mut reader, &mut writer).await;
                });
            }
        });
        let (host, mut host_updates) = launcher(11, "Host");
        let (guest, mut guest_updates) = launcher(12, "Guest");
        let code = loop {
            if let Some(team) = host_updates.recv().await.unwrap().team {
                break team.code;
            }
        };
        while guest_updates.recv().await.unwrap().team.is_none() {}
        let stranger = GameLink {
            port: host.link.port,
            secret: "forged".into(),
        };
        let mut intruder = Game::attach(&stranger).await;
        assert!(intruder.lines.next_line().await.unwrap().is_none());
        let mut host_game = Game::attach(&host.link).await;
        let mut guest_game = Game::attach(&guest.link).await;
        guest.send(Command::Join(code));
        host_game.say(r#"{"type":"presence","presence":"menu"}"#).await;
        guest_game.say(r#"{"type":"presence","presence":"ready"}"#).await;
        host_game
            .until(|state| {
                state["team"]["members"]
                    .as_array()
                    .is_some_and(|members| members.iter().any(|member| member["presence"] == "ready"))
            })
            .await;
        host_game.say(r#"{"type":"start"}"#).await;
        host_game.until(|state| !state["team"]["session"].is_null()).await;
        host_game
            .say(&format!(r#"{{"type":"opened","port":{world_port}}}"#))
            .await;
        let door = guest_game.until(|state| state["door"].is_u64()).await["door"].as_u64().unwrap();
        let mut local = TcpStream::connect(("127.0.0.1", door as u16)).await.unwrap();
        local.write_all(b"through the door").await.unwrap();
        let mut echo = [0; 16];
        local.read_exact(&mut echo).await.unwrap();
        assert_eq!(&echo, b"through the door");
        let expected = std::env::var("SUBSTRATUM_ROUTE").as_deref() != Ok("relay");
        let route = loop {
            if let Some(direct) = guest_updates.recv().await.unwrap().direct {
                break direct;
            }
        };
        assert_eq!(route, expected);
    }
}
