use std::error::Error;
use std::io;
use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use ed25519_dalek::pkcs8::{DecodePublicKey, EncodePrivateKey, EncodePublicKey};
use ed25519_dalek::{SigningKey, VerifyingKey};
use quinn::crypto::rustls::{QuicClientConfig, QuicServerConfig};
use quinn::{
    ClientConfig, Connection, Endpoint, EndpointConfig, RecvStream, SendStream, ServerConfig,
    TokioRuntime, TransportConfig,
};
use rustls::client::AlwaysResolvesClientRawPublicKeys;
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{
    CryptoProvider, WebPkiSupportedAlgorithms, verify_tls13_signature_with_raw_key,
};
use rustls::pki_types::{
    CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer, ServerName, SubjectPublicKeyInfoDer,
    UnixTime,
};
use rustls::server::AlwaysResolvesServerRawPublicKeys;
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::sign::CertifiedKey;
use rustls::version::TLS13;
use rustls::{CertificateError, DigitallySignedStruct, DistinguishedName, SignatureScheme};
use substratum_protocol::uuid;
use tokio::net::{TcpStream, UdpSocket};

const ALPN: &[u8] = b"substratum";
const SERVER_NAME: &str = "substratum";
const CONNECT_TIMEOUT: Duration = Duration::from_secs(3);
const INITIAL_RTT: Duration = Duration::from_millis(100);
const KEEP_ALIVE: Duration = Duration::from_secs(5);
const IDLE: Duration = Duration::from_secs(20);
const PUNCHES: u32 = 12;
const PUNCH_GAP: Duration = Duration::from_millis(250);

type Failure = Box<dyn Error + Send + Sync>;

pub struct Direct {
    pub endpoint: Endpoint,
    pub socket: Arc<UdpSocket>,
}

pub fn open(key: &SigningKey) -> Result<Direct, Failure> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let identity = certified(key, &provider)?;
    let verifier = Arc::new(Peer(provider.signature_verification_algorithms));
    let mut client = rustls::ClientConfig::builder_with_provider(provider.clone())
        .with_protocol_versions(&[&TLS13])?
        .dangerous()
        .with_custom_certificate_verifier(verifier.clone())
        .with_client_cert_resolver(Arc::new(AlwaysResolvesClientRawPublicKeys::new(
            identity.clone(),
        )));
    client.alpn_protocols = vec![ALPN.to_vec()];
    let mut server = rustls::ServerConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&TLS13])?
        .with_client_cert_verifier(verifier)
        .with_cert_resolver(Arc::new(AlwaysResolvesServerRawPublicKeys::new(identity)));
    server.alpn_protocols = vec![ALPN.to_vec()];
    let transport = Arc::new(transport()?);
    let mut client = ClientConfig::new(Arc::new(QuicClientConfig::try_from(client)?));
    client.transport_config(transport.clone());
    let mut server = ServerConfig::with_crypto(Arc::new(QuicServerConfig::try_from(server)?));
    server.transport_config(transport);
    let socket = std::net::UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0))?;
    let sender = socket.try_clone()?;
    sender.set_nonblocking(true)?;
    let mut endpoint = Endpoint::new(
        EndpointConfig::default(),
        Some(server),
        socket,
        Arc::new(TokioRuntime),
    )?;
    endpoint.set_default_client_config(client);
    Ok(Direct {
        endpoint,
        socket: Arc::new(UdpSocket::from_std(sender)?),
    })
}

pub fn peer(connection: &Connection) -> Option<String> {
    let keys = connection
        .peer_identity()?
        .downcast::<Vec<CertificateDer<'static>>>()
        .ok()?;
    let key = VerifyingKey::from_public_key_der(keys.first()?).ok()?;
    Some(uuid(&key.to_bytes()))
}

pub async fn punch(socket: Arc<UdpSocket>, peer: SocketAddr) {
    for _ in 0..PUNCHES {
        if let Err(error) = socket.send_to(&[0], peer).await {
            eprintln!("punching towards {peer} failed: {error}");
            return;
        }
        tokio::time::sleep(PUNCH_GAP).await;
    }
}

pub async fn connect(endpoint: &Endpoint, address: SocketAddr, host: &str) -> Option<Connection> {
    let attempt = async {
        let connection = endpoint.connect(address, SERVER_NAME)?.await?;
        if peer(&connection).as_deref() == Some(host) {
            return Ok(connection);
        }
        connection.close(0u32.into(), b"unexpected peer");
        Err::<_, Failure>("the host presented another key".into())
    };
    match tokio::time::timeout(CONNECT_TIMEOUT, attempt).await {
        Ok(Ok(connection)) => Some(connection),
        Ok(Err(error)) => {
            eprintln!("direct connection to {address} failed: {error}");
            None
        }
        Err(_) => {
            eprintln!("direct connection to {address} timed out");
            None
        }
    }
}

pub async fn serve(connection: Connection, world: u16) {
    while let Ok((send, receive)) = connection.accept_bi().await {
        tokio::spawn(async move {
            let result = async {
                let local = TcpStream::connect((Ipv4Addr::LOCALHOST, world)).await?;
                bridge(send, receive, local).await
            };
            if let Err(error) = result.await {
                eprintln!("direct stream to the local world failed: {error}");
            }
        });
    }
}

pub async fn bridge(send: SendStream, receive: RecvStream, mut local: TcpStream) -> io::Result<()> {
    local.set_nodelay(true)?;
    tokio::io::copy_bidirectional(&mut tokio::io::join(receive, send), &mut local).await?;
    Ok(())
}

fn transport() -> Result<TransportConfig, Failure> {
    let mut transport = TransportConfig::default();
    transport
        .initial_rtt(INITIAL_RTT)
        .keep_alive_interval(Some(KEEP_ALIVE))
        .max_idle_timeout(Some(IDLE.try_into()?));
    Ok(transport)
}

fn certified(key: &SigningKey, provider: &CryptoProvider) -> Result<Arc<CertifiedKey>, Failure> {
    let private = PrivatePkcs8KeyDer::from(key.to_pkcs8_der()?.as_bytes().to_vec());
    let public = CertificateDer::from(key.verifying_key().to_public_key_der()?.into_vec());
    let signer = provider
        .key_provider
        .load_private_key(PrivateKeyDer::Pkcs8(private))?;
    Ok(Arc::new(CertifiedKey::new(vec![public], signer)))
}

#[derive(Debug)]
struct Peer(WebPkiSupportedAlgorithms);

impl Peer {
    fn check(key: &[u8]) -> Result<(), rustls::Error> {
        VerifyingKey::from_public_key_der(key)
            .map(drop)
            .map_err(|_| rustls::Error::InvalidCertificate(CertificateError::BadEncoding))
    }

    fn verify(
        &self,
        message: &[u8],
        key: &CertificateDer<'_>,
        signature: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        let key = SubjectPublicKeyInfoDer::from(key.as_ref());
        verify_tls13_signature_with_raw_key(message, &key, signature, &self.0)
    }
}

impl ServerCertVerifier for Peer {
    fn verify_server_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Self::check(end_entity).map(|()| ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Err(rustls::Error::General("TLS 1.2 is not offered".into()))
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.verify(message, cert, dss)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![SignatureScheme::ED25519]
    }

    fn requires_raw_public_keys(&self) -> bool {
        true
    }
}

impl ClientCertVerifier for Peer {
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> Result<ClientCertVerified, rustls::Error> {
        Self::check(end_entity).map(|()| ClientCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        Err(rustls::Error::General("TLS 1.2 is not offered".into()))
    }

    fn verify_tls13_signature(
        &self,
        message: &[u8],
        cert: &CertificateDer<'_>,
        dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, rustls::Error> {
        self.verify(message, cert, dss)
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![SignatureScheme::ED25519]
    }

    fn requires_raw_public_keys(&self) -> bool {
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn peers_prove_their_profile_keys() {
        let host_key = SigningKey::from_bytes(&[1; 32]);
        let guest_key = SigningKey::from_bytes(&[2; 32]);
        let host = open(&host_key).unwrap();
        let guest = open(&guest_key).unwrap();
        let port = host.endpoint.local_addr().unwrap().port();
        let address = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
        let (accepted, mut connections) = tokio::sync::mpsc::unbounded_channel();
        tokio::spawn(async move {
            while let Some(incoming) = host.endpoint.accept().await {
                if let Ok(connection) = incoming.await {
                    let _ = accepted.send(connection);
                }
            }
        });
        let host_uuid = uuid(&host_key.verifying_key().to_bytes());
        let reached = connect(&guest.endpoint, address, &host_uuid).await.unwrap();
        let incoming = connections.recv().await.unwrap();
        assert_eq!(
            peer(&incoming),
            Some(uuid(&guest_key.verifying_key().to_bytes()))
        );
        let (mut send, mut receive) = reached.open_bi().await.unwrap();
        send.write_all(b"ping").await.unwrap();
        send.finish().unwrap();
        let (mut back, mut read) = incoming.accept_bi().await.unwrap();
        assert_eq!(read.read_to_end(16).await.unwrap(), b"ping");
        back.write_all(b"pong").await.unwrap();
        back.finish().unwrap();
        assert_eq!(receive.read_to_end(16).await.unwrap(), b"pong");
        assert!(
            connect(&guest.endpoint, address, "someone-else")
                .await
                .is_none()
        );
    }
}
