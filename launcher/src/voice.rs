use std::error::Error;
use std::io;
use std::net::{Ipv4Addr, SocketAddr};

use futures_util::{SinkExt, StreamExt};
use quinn::{Connection, ConnectionError, SendDatagramError};
use tokio::net::{TcpStream, UdpSocket};
use tokio::sync::watch;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::{MaybeTlsStream, WebSocketStream};

type Failure = Box<dyn Error + Send + Sync>;

pub type Relayed = WebSocketStream<MaybeTlsStream<TcpStream>>;

const DATAGRAM_MAX: usize = 4096;

pub enum Tunnel {
    Direct(Connection),
    Relay(Box<Relayed>),
}

pub enum Local {
    Game(Option<SocketAddr>),
    Server(watch::Receiver<Option<u16>>),
}

impl Tunnel {
    async fn send(&mut self, datagram: &[u8]) -> Result<(), Failure> {
        match self {
            Tunnel::Direct(connection) => {
                match connection.send_datagram(datagram.to_vec().into()) {
                    Ok(()) | Err(SendDatagramError::TooLarge) => Ok(()),
                    Err(error) => Err(error.into()),
                }
            }
            Tunnel::Relay(socket) => Ok(socket.send(Message::binary(datagram.to_vec())).await?),
        }
    }

    async fn receive(&mut self) -> Result<Option<Vec<u8>>, Failure> {
        match self {
            Tunnel::Direct(connection) => match connection.read_datagram().await {
                Ok(datagram) => Ok(Some(datagram.to_vec())),
                Err(ConnectionError::ApplicationClosed(_) | ConnectionError::LocallyClosed) => {
                    Ok(None)
                }
                Err(error) => Err(error.into()),
            },
            Tunnel::Relay(socket) => loop {
                match socket.next().await.transpose()? {
                    Some(Message::Binary(datagram)) => return Ok(Some(datagram.to_vec())),
                    Some(Message::Close(_)) | None => return Ok(None),
                    Some(_) => {}
                }
            },
        }
    }
}

impl Local {
    fn admit(&mut self, from: SocketAddr) -> bool {
        match self {
            Local::Game(game) => {
                *game = Some(from);
                true
            }
            Local::Server(_) => self.target() == Some(from),
        }
    }

    fn target(&self) -> Option<SocketAddr> {
        match self {
            Local::Game(game) => *game,
            Local::Server(port) => port
                .borrow()
                .map(|port| SocketAddr::from((Ipv4Addr::LOCALHOST, port))),
        }
    }
}

pub async fn host(tunnel: Tunnel, server: watch::Receiver<Option<u16>>) -> Result<(), Failure> {
    let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await?;
    pump(&socket, Local::Server(server), tunnel).await
}

pub async fn pump(socket: &UdpSocket, mut local: Local, mut tunnel: Tunnel) -> Result<(), Failure> {
    let mut buffer = vec![0; DATAGRAM_MAX];
    loop {
        tokio::select! {
            received = socket.recv_from(&mut buffer) => {
                let Some((size, from)) = arrived(received)? else { continue };
                if local.admit(from) {
                    tunnel.send(&buffer[..size]).await?;
                }
            }
            datagram = tunnel.receive() => {
                let Some(datagram) = datagram? else { return Ok(()) };
                if let Some(target) = local.target() {
                    socket.send_to(&datagram, target).await?;
                }
            }
        }
    }
}

fn arrived(received: io::Result<(usize, SocketAddr)>) -> io::Result<Option<(usize, SocketAddr)>> {
    match received {
        Ok(received) => Ok(Some(received)),
        Err(error) if error.kind() == io::ErrorKind::ConnectionReset => Ok(None),
        Err(error) => Err(error),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_server_only_hears_its_own_voice_port() {
        let (port, receiver) = watch::channel(Some(24454));
        let mut local = Local::Server(receiver);
        assert!(local.admit(SocketAddr::from((Ipv4Addr::LOCALHOST, 24454))));
        assert!(!local.admit(SocketAddr::from((Ipv4Addr::LOCALHOST, 24455))));
        port.send_replace(None);
        assert_eq!(local.target(), None);
    }

    #[test]
    fn the_game_is_found_by_its_first_datagram() {
        let mut local = Local::Game(None);
        assert_eq!(local.target(), None);
        let game = SocketAddr::from((Ipv4Addr::LOCALHOST, 50000));
        assert!(local.admit(game));
        assert_eq!(local.target(), Some(game));
    }

    #[test]
    fn a_closed_game_port_does_not_end_the_tunnel() {
        let reset = io::Error::from(io::ErrorKind::ConnectionReset);
        assert!(matches!(arrived(Err(reset)), Ok(None)));
        assert!(arrived(Err(io::Error::from(io::ErrorKind::Other))).is_err());
    }
}
