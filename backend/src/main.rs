mod hub;

use std::collections::HashMap;
use std::collections::hash_map::Entry;
use std::error::Error;
use std::future::ready;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex, MutexGuard, PoisonError};
use std::time::Duration;

use axum::Router;
use axum::extract::State;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::http::{HeaderMap, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::get;
use axum::serve::ListenerExt;
use futures_util::stream::{SplitSink, SplitStream};
use futures_util::{SinkExt, StreamExt};
use substratum_protocol::{
    BEACON_LENGTH, CODE_ALPHABET, CODE_LENGTH, CONTROL_PATH, Profile, RELAY_CHUNK, RELAY_PATH,
    Refusal, Relay, ToClient, ToServer, hex, verify_hello,
};
use tokio::net::UdpSocket;
use tokio::sync::{mpsc, oneshot};
use tokio::time::{Instant, timeout};

use hub::{Hub, Keys};

const DEFAULT_BIND: &str = "127.0.0.1:7878";
const DEFAULT_BEACON: &str = "0.0.0.0:7878";
const CLIENT_HEADER: &str = "x-real-ip";
const HELLO_TIMEOUT: Duration = Duration::from_secs(10);
const ACCEPT_TIMEOUT: Duration = Duration::from_secs(10);
const SEND_TIMEOUT: Duration = Duration::from_secs(10);
const GRACE: Duration = Duration::from_secs(30);
const PING: Duration = Duration::from_secs(20);
const IDLE: Duration = Duration::from_secs(60);
const RATE_WINDOW: Duration = Duration::from_secs(10);
const RATE_LIMIT: u32 = 50;
const CONTROL_LIMIT: usize = 4 * 1024;
const MAX_CONNECTIONS: usize = 4096;
const MAX_PER_ADDRESS: usize = 32;

#[derive(Clone, Default)]
struct App {
    hub: Arc<Mutex<Hub>>,
    streams: Arc<Mutex<Streams>>,
    peers: Arc<Mutex<Peers>>,
}

#[derive(Default)]
struct Streams {
    next: u64,
    waiting: HashMap<u64, Waiting>,
}

struct Waiting {
    host: String,
    socket: oneshot::Sender<WebSocket>,
}

#[derive(Default)]
struct Peers {
    total: usize,
    by_address: HashMap<IpAddr, usize>,
}

struct Ticket {
    peers: Arc<Mutex<Peers>>,
    address: Option<IpAddr>,
}

impl Drop for Ticket {
    fn drop(&mut self) {
        let mut peers = lock(&self.peers);
        peers.total -= 1;
        if let Some(address) = self.address
            && let Entry::Occupied(mut entry) = peers.by_address.entry(address)
        {
            *entry.get_mut() -= 1;
            if *entry.get() == 0 {
                entry.remove();
            }
        }
    }
}

type Sink = SplitSink<WebSocket, Message>;

#[tokio::main]
async fn main() -> Result<(), Box<dyn Error>> {
    let address: SocketAddr = std::env::var("SUBSTRATUM_BIND")
        .unwrap_or_else(|_| DEFAULT_BIND.to_owned())
        .parse()?;
    let beacon: SocketAddr = std::env::var("SUBSTRATUM_BEACON")
        .unwrap_or_else(|_| DEFAULT_BEACON.to_owned())
        .parse()?;
    let app = App::default();
    let beacons = UdpSocket::bind(beacon).await?;
    tokio::spawn(listen(beacons, app.hub.clone()));
    let router = Router::new()
        .route(CONTROL_PATH, get(control))
        .route(RELAY_PATH, get(relay))
        .with_state(app);
    let listener = tokio::net::TcpListener::bind(address)
        .await?
        .tap_io(|socket| {
            let _ = socket.set_nodelay(true);
        });
    println!("substratum-backend listening on {address}, beacons on udp {beacon}");
    axum::serve(listener, router).await?;
    Ok(())
}

async fn listen(socket: UdpSocket, hub: Arc<Mutex<Hub>>) {
    let mut buffer = [0; BEACON_LENGTH + 1];
    loop {
        match socket.recv_from(&mut buffer).await {
            Ok((BEACON_LENGTH, from)) => {
                if let Ok(beacon) = buffer[..BEACON_LENGTH].try_into() {
                    lock(&hub).locate(&beacon, from);
                }
            }
            Ok(_) => {}
            Err(error) => eprintln!("beacon receive failed: {error}"),
        }
    }
}

fn admit(app: &App, headers: &HeaderMap) -> Option<Ticket> {
    let address = headers
        .get(CLIENT_HEADER)
        .and_then(|value| value.to_str().ok())
        .and_then(|value| value.trim().parse().ok());
    let mut peers = lock(&app.peers);
    if peers.total >= MAX_CONNECTIONS {
        return None;
    }
    if let Some(address) = address {
        let count = peers.by_address.entry(address).or_default();
        if *count >= MAX_PER_ADDRESS {
            return None;
        }
        *count += 1;
    }
    peers.total += 1;
    Some(Ticket {
        peers: app.peers.clone(),
        address,
    })
}

async fn control(State(app): State<App>, headers: HeaderMap, upgrade: WebSocketUpgrade) -> Response {
    let Some(ticket) = admit(&app, &headers) else {
        return StatusCode::TOO_MANY_REQUESTS.into_response();
    };
    upgrade
        .max_message_size(CONTROL_LIMIT)
        .max_frame_size(CONTROL_LIMIT)
        .on_upgrade(move |socket| async move {
            serve_control(socket, app).await;
            drop(ticket);
        })
}

async fn serve_control(socket: WebSocket, app: App) {
    let (mut sink, mut stream) = socket.split();
    let nonce = hex(&random_bytes::<16>());
    let challenge = ToClient::Challenge {
        nonce: nonce.clone(),
    };
    if !send(&mut sink, &challenge).await {
        return;
    }
    let Some((uuid, profile)) = greet(&mut stream, &nonce).await else {
        return;
    };
    let (outbox, mut inbox) = mpsc::unbounded_channel();
    let keys = Keys {
        token: hex(&random_bytes::<32>()),
        beacon: random_bytes(),
    };
    let generation = lock(&app.hub).connect(&uuid, profile, keys, outbox);
    let mut ping = tokio::time::interval(PING);
    let mut heard = Instant::now();
    let mut window = heard;
    let mut frames = 0;
    loop {
        tokio::select! {
            message = inbox.recv() => {
                let Some(message) = message else { break };
                if !send(&mut sink, &message).await {
                    break;
                }
            }
            frame = stream.next() => {
                let Some(Ok(frame)) = frame else { break };
                heard = Instant::now();
                if heard - window > RATE_WINDOW {
                    window = heard;
                    frames = 0;
                }
                frames += 1;
                if frames > RATE_LIMIT {
                    break;
                }
                match frame {
                    Message::Text(text) => {
                        let result = match serde_json::from_str::<ToServer>(text.as_str()) {
                            Ok(message) => lock(&app.hub).handle(&uuid, message),
                            Err(_) => Err(Refusal::Invalid),
                        };
                        if let Err(reason) = result
                            && !send(&mut sink, &ToClient::Refused { reason }).await
                        {
                            break;
                        }
                    }
                    Message::Close(_) => break,
                    _ => {}
                }
            }
            _ = ping.tick() => {
                if heard.elapsed() > IDLE || !transmit(&mut sink, Message::Ping(Default::default())).await {
                    break;
                }
            }
        }
    }
    if lock(&app.hub).disconnect(&uuid, generation) {
        tokio::spawn(async move {
            tokio::time::sleep(GRACE).await;
            lock(&app.hub).expire(&uuid, generation);
        });
    }
}

async fn greet(stream: &mut SplitStream<WebSocket>, nonce: &str) -> Option<(String, Profile)> {
    let frame = timeout(HELLO_TIMEOUT, stream.next()).await.ok()??.ok()?;
    let Message::Text(text) = frame else {
        return None;
    };
    let ToServer::Hello {
        key,
        signature,
        profile,
    } = serde_json::from_str(text.as_str()).ok()?
    else {
        return None;
    };
    if !profile.valid() {
        return None;
    }
    let uuid = verify_hello(&key, &signature, nonce)?;
    Some((uuid, profile))
}

async fn relay(State(app): State<App>, headers: HeaderMap, upgrade: WebSocketUpgrade) -> Response {
    let Some(ticket) = admit(&app, &headers) else {
        return StatusCode::TOO_MANY_REQUESTS.into_response();
    };
    upgrade
        .max_message_size(RELAY_CHUNK)
        .max_frame_size(RELAY_CHUNK)
        .on_upgrade(move |socket| async move {
            serve_relay(socket, app).await;
            drop(ticket);
        })
}

async fn serve_relay(mut socket: WebSocket, app: App) {
    let Ok(Some(Ok(Message::Text(text)))) = timeout(HELLO_TIMEOUT, socket.recv()).await else {
        return;
    };
    match serde_json::from_str::<Relay>(text.as_str()) {
        Ok(Relay::Dial { token }) => dial(socket, &app, &token).await,
        Ok(Relay::Accept { token, stream }) => accept(socket, &app, &token, stream),
        Err(_) => {}
    }
}

async fn dial(socket: WebSocket, app: &App, token: &str) {
    let route = {
        let hub = lock(&app.hub);
        hub.owner(token)
            .ok_or(Refusal::Invalid)
            .and_then(|uuid| hub.dial(uuid))
    };
    let Ok((host, outbox)) = route else {
        return;
    };
    let (sender, receiver) = oneshot::channel();
    let stream = {
        let mut streams = lock(&app.streams);
        streams.next += 1;
        let stream = streams.next;
        let waiting = Waiting {
            host,
            socket: sender,
        };
        streams.waiting.insert(stream, waiting);
        stream
    };
    let accepted = match outbox.send(ToClient::Incoming { stream }) {
        Ok(()) => timeout(ACCEPT_TIMEOUT, receiver).await.ok().and_then(Result::ok),
        Err(_) => None,
    };
    lock(&app.streams).waiting.remove(&stream);
    if let Some(host) = accepted {
        pipe(socket, host).await;
    }
}

fn accept(socket: WebSocket, app: &App, token: &str, stream: u64) {
    let Some(owner) = lock(&app.hub).owner(token).map(str::to_owned) else {
        return;
    };
    let mut streams = lock(&app.streams);
    if streams
        .waiting
        .get(&stream)
        .is_some_and(|waiting| waiting.host == owner)
        && let Some(waiting) = streams.waiting.remove(&stream)
    {
        let _ = waiting.socket.send(socket);
    }
}

async fn pipe(guest: WebSocket, host: WebSocket) {
    let (mut guest_sink, mut guest_stream) = guest.split();
    let (mut host_sink, mut host_stream) = host.split();
    tokio::select! {
        () = forward(&mut guest_stream, &mut host_sink) => {}
        () = forward(&mut host_stream, &mut guest_sink) => {}
    }
    let _ = guest_sink.close().await;
    let _ = host_sink.close().await;
}

async fn forward(from: &mut SplitStream<WebSocket>, to: &mut Sink) {
    let _ = from
        .take_while(|frame| ready(!matches!(frame, Ok(Message::Close(_)) | Err(_))))
        .filter(|frame| ready(matches!(frame, Ok(Message::Binary(_)))))
        .forward(to)
        .await;
}

async fn send(sink: &mut Sink, message: &ToClient) -> bool {
    let text = serde_json::to_string(message).expect("protocol messages always serialize");
    transmit(sink, Message::Text(text.into())).await
}

async fn transmit(sink: &mut Sink, message: Message) -> bool {
    matches!(timeout(SEND_TIMEOUT, sink.send(message)).await, Ok(Ok(())))
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

fn random_bytes<const N: usize>() -> [u8; N] {
    let mut bytes = [0; N];
    getrandom::fill(&mut bytes).expect("the system has no randomness source");
    bytes
}

fn random_code() -> String {
    random_bytes::<CODE_LENGTH>()
        .iter()
        .map(|byte| CODE_ALPHABET[*byte as usize % CODE_ALPHABET.len()] as char)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connections_are_limited_per_address() {
        let app = App::default();
        let mut headers = HeaderMap::new();
        headers.insert(CLIENT_HEADER, "203.0.113.7".parse().unwrap());
        let tickets: Vec<_> = (0..MAX_PER_ADDRESS).map(|_| admit(&app, &headers).unwrap()).collect();
        assert!(admit(&app, &headers).is_none());
        let anonymous = admit(&app, &HeaderMap::new()).unwrap();
        drop(tickets);
        assert!(admit(&app, &headers).is_some());
        drop(anonymous);
        assert_eq!(lock(&app.peers).total, 0);
        assert!(lock(&app.peers).by_address.is_empty());
    }
}
