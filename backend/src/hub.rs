use std::collections::HashMap;

use substratum_protocol::{
    MAX_PLAYERS, Member, Presence, Profile, Refusal, Session, Team, ToClient, ToServer, valid_code,
};
use tokio::sync::mpsc::UnboundedSender;

pub type Outbox = UnboundedSender<ToClient>;

struct Player {
    code: String,
    outbox: Option<Outbox>,
    generation: u64,
    token: String,
}

#[derive(Default)]
pub struct Hub {
    teams: HashMap<String, Team>,
    players: HashMap<String, Player>,
    tokens: HashMap<String, String>,
    dirty: Vec<String>,
}

impl Hub {
    pub fn connect(&mut self, uuid: &str, profile: Profile, token: String, outbox: Outbox) -> u64 {
        let generation = self.attach(uuid, profile, token, outbox);
        self.flush();
        generation
    }

    pub fn disconnect(&mut self, uuid: &str, generation: u64) -> bool {
        let detached = self.detach(uuid, generation);
        self.flush();
        detached
    }

    pub fn expire(&mut self, uuid: &str, generation: u64) {
        let Some(player) = self.players.get(uuid) else {
            return;
        };
        if player.generation != generation || player.outbox.is_some() {
            return;
        }
        if self.teams.get(&player.code).is_some_and(|team| team.takes_part(uuid)) {
            return;
        }
        self.forget(uuid);
        self.flush();
    }

    pub fn owner(&self, token: &str) -> Option<&str> {
        self.tokens.get(token).map(String::as_str)
    }

    pub fn handle(&mut self, uuid: &str, message: ToServer) -> Result<(), Refusal> {
        let result = match message {
            ToServer::Hello { .. } => Err(Refusal::Invalid),
            ToServer::Profile(profile) => self.profile(uuid, profile),
            ToServer::Join { code } => self.join(uuid, &code),
            ToServer::Leave => self.leave(uuid),
            ToServer::Kick { uuid: target } => self.kick(uuid, &target),
            ToServer::Presence { presence } => self.presence(uuid, presence),
            ToServer::Start => self.start(uuid),
            ToServer::Opened => self.opened(uuid),
            ToServer::Ended => self.ended(uuid),
        };
        self.flush();
        result
    }

    pub fn dial(&self, uuid: &str) -> Result<(String, Outbox), Refusal> {
        let team = self.team_of(uuid).ok_or(Refusal::Invalid)?;
        let open = team.session.as_ref().is_some_and(|session| session.open);
        if !open || team.host == uuid || !team.takes_part(uuid) {
            return Err(Refusal::Locked);
        }
        let host = self.players.get(&team.host).ok_or(Refusal::Invalid)?;
        let outbox = host.outbox.clone().ok_or(Refusal::Invalid)?;
        Ok((team.host.clone(), outbox))
    }

    fn attach(&mut self, uuid: &str, profile: Profile, token: String, outbox: Outbox) -> u64 {
        let welcome = ToClient::Welcome {
            uuid: uuid.to_owned(),
            token: token.clone(),
        };
        let _ = outbox.send(welcome);
        let Some(player) = self.players.get_mut(uuid) else {
            let member = Member {
                uuid: uuid.to_owned(),
                nick: profile.nick,
                skin: profile.skin,
                build: profile.build,
                presence: Presence::Away,
                online: true,
            };
            let code = self.found(member);
            self.tokens.insert(token.clone(), uuid.to_owned());
            self.players.insert(
                uuid.to_owned(),
                Player {
                    code: code.clone(),
                    outbox: Some(outbox),
                    generation: 0,
                    token,
                },
            );
            self.touch(&code);
            return 0;
        };
        let stale = std::mem::replace(&mut player.token, token.clone());
        self.tokens.remove(&stale);
        self.tokens.insert(token, uuid.to_owned());
        player.generation += 1;
        player.outbox = Some(outbox);
        let (code, generation) = (player.code.clone(), player.generation);
        if let Some(team) = self.teams.get_mut(&code) {
            let taken = team
                .members
                .iter()
                .any(|member| member.uuid != uuid && member.nick == profile.nick);
            if let Some(member) = team.members.iter_mut().find(|member| member.uuid == uuid) {
                member.online = true;
                member.presence = Presence::Away;
                member.skin = profile.skin;
                member.build = profile.build;
                if !taken {
                    member.nick = profile.nick;
                }
            }
        }
        self.touch(&code);
        generation
    }

    fn detach(&mut self, uuid: &str, generation: u64) -> bool {
        let Some(player) = self.players.get_mut(uuid) else {
            return false;
        };
        if player.generation != generation {
            return false;
        }
        player.outbox = None;
        let code = player.code.clone();
        let Some(team) = self.teams.get_mut(&code) else {
            return false;
        };
        if let Some(member) = team.members.iter_mut().find(|member| member.uuid == uuid) {
            member.online = false;
            member.presence = Presence::Away;
        }
        if team.host == uuid && team.session.is_some() {
            self.end(&code);
        }
        self.touch(&code);
        true
    }

    fn profile(&mut self, uuid: &str, profile: Profile) -> Result<(), Refusal> {
        if !profile.valid() {
            return Err(Refusal::Invalid);
        }
        let code = self.code_of(uuid)?;
        let team = self.teams.get_mut(&code).ok_or(Refusal::Invalid)?;
        if team.session.is_some() {
            return Err(Refusal::Locked);
        }
        if team
            .members
            .iter()
            .any(|member| member.uuid != uuid && member.nick == profile.nick)
        {
            return Err(Refusal::NickTaken);
        }
        let member = team
            .members
            .iter_mut()
            .find(|member| member.uuid == uuid)
            .ok_or(Refusal::Invalid)?;
        member.nick = profile.nick;
        member.skin = profile.skin;
        member.build = profile.build;
        self.touch(&code);
        Ok(())
    }

    fn join(&mut self, uuid: &str, code: &str) -> Result<(), Refusal> {
        if !valid_code(code) {
            return Err(Refusal::Unknown);
        }
        let current = self.code_of(uuid)?;
        if current == code {
            return Ok(());
        }
        let target = self.teams.get(code).ok_or(Refusal::Unknown)?;
        let mine = self.teams.get(&current).ok_or(Refusal::Invalid)?;
        if target.session.is_some() || mine.session.is_some() {
            return Err(Refusal::Locked);
        }
        if target.members.len() >= MAX_PLAYERS {
            return Err(Refusal::Full);
        }
        let member = mine.member(uuid).ok_or(Refusal::Invalid)?.clone();
        if target.members.iter().any(|other| other.nick == member.nick) {
            return Err(Refusal::NickTaken);
        }
        self.depart(uuid);
        self.settle(member, code);
        Ok(())
    }

    fn leave(&mut self, uuid: &str) -> Result<(), Refusal> {
        let team = self.team_of(uuid).ok_or(Refusal::Invalid)?;
        if team.session.is_some() {
            return Err(Refusal::Locked);
        }
        if team.members.len() == 1 {
            return Ok(());
        }
        let member = team.member(uuid).ok_or(Refusal::Invalid)?.clone();
        self.depart(uuid);
        self.settle_alone(member);
        Ok(())
    }

    fn kick(&mut self, uuid: &str, target: &str) -> Result<(), Refusal> {
        let team = self.team_of(uuid).ok_or(Refusal::Invalid)?;
        if team.host != uuid {
            return Err(Refusal::NotHost);
        }
        if team.session.is_some() {
            return Err(Refusal::Locked);
        }
        if target == uuid {
            return Err(Refusal::Invalid);
        }
        let member = team.member(target).ok_or(Refusal::Invalid)?.clone();
        self.depart(target);
        self.settle_alone(member);
        Ok(())
    }

    fn presence(&mut self, uuid: &str, presence: Presence) -> Result<(), Refusal> {
        let code = self.code_of(uuid)?;
        let team = self.teams.get_mut(&code).ok_or(Refusal::Invalid)?;
        let member = team
            .members
            .iter_mut()
            .find(|member| member.uuid == uuid)
            .ok_or(Refusal::Invalid)?;
        if member.presence == presence {
            return Ok(());
        }
        member.presence = presence;
        self.touch(&code);
        Ok(())
    }

    fn start(&mut self, uuid: &str) -> Result<(), Refusal> {
        let code = self.code_of(uuid)?;
        let team = self.teams.get_mut(&code).ok_or(Refusal::Invalid)?;
        if team.host != uuid {
            return Err(Refusal::NotHost);
        }
        if team.session.is_some() {
            return Err(Refusal::Locked);
        }
        let host = team.member(uuid).ok_or(Refusal::Invalid)?;
        let ready = host.presence == Presence::Menu
            && team.members.iter().filter(|member| member.uuid != uuid).all(|member| {
                member.online && member.presence == Presence::Ready && member.build == host.build
            });
        if !ready {
            return Err(Refusal::NotReady);
        }
        team.session = Some(Session {
            participants: team.members.iter().map(|member| member.uuid.clone()).collect(),
            open: false,
        });
        unready(team);
        self.touch(&code);
        Ok(())
    }

    fn opened(&mut self, uuid: &str) -> Result<(), Refusal> {
        let code = self.code_of(uuid)?;
        let team = self.teams.get_mut(&code).ok_or(Refusal::Invalid)?;
        if team.host != uuid {
            return Err(Refusal::NotHost);
        }
        let session = team.session.as_mut().ok_or(Refusal::Invalid)?;
        session.open = true;
        self.touch(&code);
        Ok(())
    }

    fn ended(&mut self, uuid: &str) -> Result<(), Refusal> {
        let code = self.code_of(uuid)?;
        let team = self.teams.get(&code).ok_or(Refusal::Invalid)?;
        if team.host != uuid {
            return Err(Refusal::NotHost);
        }
        if team.session.is_some() {
            self.end(&code);
            self.touch(&code);
        }
        Ok(())
    }

    fn end(&mut self, code: &str) {
        let Some(team) = self.teams.get_mut(code) else {
            return;
        };
        team.session = None;
        unready(team);
        let host = team.host.clone();
        let gone: Vec<String> = team
            .members
            .iter()
            .filter(|member| !member.online && member.uuid != host)
            .map(|member| member.uuid.clone())
            .collect();
        for uuid in gone {
            self.forget(&uuid);
        }
    }

    fn forget(&mut self, uuid: &str) {
        self.depart(uuid);
        if let Some(player) = self.players.remove(uuid) {
            self.tokens.remove(&player.token);
        }
    }

    fn depart(&mut self, uuid: &str) {
        let Some(code) = self.players.get(uuid).map(|player| player.code.clone()) else {
            return;
        };
        let Some(team) = self.teams.get_mut(&code) else {
            return;
        };
        team.members.retain(|member| member.uuid != uuid);
        if team.host != uuid {
            self.touch(&code);
            return;
        }
        let Some(team) = self.teams.remove(&code) else {
            return;
        };
        for member in team.members {
            self.settle_alone(member);
        }
    }

    fn settle(&mut self, member: Member, code: &str) {
        let uuid = member.uuid.clone();
        if let Some(team) = self.teams.get_mut(code) {
            team.members.push(member);
        }
        if let Some(player) = self.players.get_mut(&uuid) {
            player.code = code.to_owned();
        }
        self.touch(code);
    }

    fn settle_alone(&mut self, member: Member) {
        let uuid = member.uuid.clone();
        let code = self.found(member);
        if let Some(player) = self.players.get_mut(&uuid) {
            player.code = code.clone();
        }
        self.touch(&code);
    }

    fn found(&mut self, member: Member) -> String {
        let code = loop {
            let code = crate::random_code();
            if !self.teams.contains_key(&code) {
                break code;
            }
        };
        let team = Team {
            code: code.clone(),
            host: member.uuid.clone(),
            members: vec![member],
            session: None,
        };
        self.teams.insert(code.clone(), team);
        code
    }

    fn touch(&mut self, code: &str) {
        if !self.dirty.iter().any(|dirty| dirty == code) {
            self.dirty.push(code.to_owned());
        }
    }

    fn flush(&mut self) {
        for code in std::mem::take(&mut self.dirty) {
            self.publish(&code);
        }
    }

    fn publish(&self, code: &str) {
        let Some(team) = self.teams.get(code) else {
            return;
        };
        for member in &team.members {
            let outbox = self
                .players
                .get(&member.uuid)
                .and_then(|player| player.outbox.as_ref());
            if let Some(outbox) = outbox {
                let _ = outbox.send(ToClient::Team { team: team.clone() });
            }
        }
    }

    fn code_of(&self, uuid: &str) -> Result<String, Refusal> {
        self.players
            .get(uuid)
            .map(|player| player.code.clone())
            .ok_or(Refusal::Invalid)
    }

    fn team_of(&self, uuid: &str) -> Option<&Team> {
        self.teams.get(&self.players.get(uuid)?.code)
    }
}

fn unready(team: &mut Team) {
    for member in &mut team.members {
        if member.presence == Presence::Ready {
            member.presence = Presence::Menu;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::sync::mpsc::{UnboundedReceiver, unbounded_channel};

    fn connect(hub: &mut Hub, uuid: &str, nick: &str) -> UnboundedReceiver<ToClient> {
        let (outbox, inbox) = unbounded_channel();
        let profile = Profile {
            nick: nick.into(),
            skin: "yellow".into(),
            build: "0.1.0".into(),
        };
        hub.connect(uuid, profile, format!("token-{uuid}"), outbox);
        inbox
    }

    fn last_team(inbox: &mut UnboundedReceiver<ToClient>) -> Team {
        let mut team = None;
        while let Ok(message) = inbox.try_recv() {
            if let ToClient::Team { team: latest } = message {
                team = Some(latest);
            }
        }
        team.expect("no team snapshot")
    }

    fn code(hub: &Hub, uuid: &str) -> String {
        hub.code_of(uuid).unwrap()
    }

    #[test]
    fn players_join_by_code_and_the_host_starts_when_everyone_is_ready() {
        let mut hub = Hub::default();
        let mut host = connect(&mut hub, "h", "Host");
        let mut guest = connect(&mut hub, "g", "Guest");
        let team = code(&hub, "h");
        hub.handle("g", ToServer::Join { code: team.clone() }).unwrap();
        assert_eq!(last_team(&mut guest).members.len(), 2);
        hub.handle("h", ToServer::Presence { presence: Presence::Menu }).unwrap();
        assert_eq!(hub.handle("h", ToServer::Start), Err(Refusal::NotReady));
        hub.handle("g", ToServer::Presence { presence: Presence::Ready }).unwrap();
        assert_eq!(hub.handle("g", ToServer::Start), Err(Refusal::NotHost));
        hub.handle("h", ToServer::Start).unwrap();
        let started = last_team(&mut host);
        let session = started.session.clone().unwrap();
        assert_eq!(session.participants, ["h", "g"]);
        assert!(!session.open);
        assert_eq!(started.member("g").unwrap().presence, Presence::Menu);
        assert_eq!(hub.dial("g").err(), Some(Refusal::Locked));
        hub.handle("h", ToServer::Opened).unwrap();
        assert_eq!(hub.dial("g").unwrap().0, "h");
        assert_eq!(hub.handle("g", ToServer::Leave), Err(Refusal::Locked));
        hub.handle("h", ToServer::Ended).unwrap();
        assert!(last_team(&mut guest).session.is_none());
    }

    #[test]
    fn joining_respects_size_names_and_running_games() {
        let mut hub = Hub::default();
        let _host = connect(&mut hub, "h", "Host");
        let team = code(&hub, "h");
        for (uuid, nick) in [("a", "Alpha"), ("b", "Bravo"), ("c", "Charlie")] {
            let _ = connect(&mut hub, uuid, nick);
            hub.handle(uuid, ToServer::Join { code: team.clone() }).unwrap();
        }
        let _late = connect(&mut hub, "d", "Delta");
        assert_eq!(hub.handle("d", ToServer::Join { code: team.clone() }), Err(Refusal::Full));
        hub.handle("h", ToServer::Kick { uuid: "c".into() }).unwrap();
        let _twin = connect(&mut hub, "e", "Alpha");
        assert_eq!(hub.handle("e", ToServer::Join { code: team.clone() }), Err(Refusal::NickTaken));
        assert_eq!(hub.handle("d", ToServer::Join { code: "ZZZZZZZZ".into() }), Err(Refusal::Unknown));
        assert_ne!(code(&hub, "c"), team);
    }

    #[test]
    fn a_leaving_host_dissolves_the_team() {
        let mut hub = Hub::default();
        let _host = connect(&mut hub, "h", "Host");
        let mut guest = connect(&mut hub, "g", "Guest");
        let team = code(&hub, "h");
        hub.handle("g", ToServer::Join { code: team.clone() }).unwrap();
        assert!(hub.disconnect("h", 0));
        hub.expire("h", 0);
        let alone = last_team(&mut guest);
        assert_eq!(alone.host, "g");
        assert_eq!(alone.members.len(), 1);
        assert_ne!(alone.code, team);
    }

    #[test]
    fn participants_keep_their_slot_until_the_game_ends() {
        let mut hub = Hub::default();
        let mut host = connect(&mut hub, "h", "Host");
        let _guest = connect(&mut hub, "g", "Guest");
        hub.handle("g", ToServer::Join { code: code(&hub, "h") }).unwrap();
        hub.handle("h", ToServer::Presence { presence: Presence::Menu }).unwrap();
        hub.handle("g", ToServer::Presence { presence: Presence::Ready }).unwrap();
        hub.handle("h", ToServer::Start).unwrap();
        assert!(hub.disconnect("g", 0));
        hub.expire("g", 0);
        assert_eq!(last_team(&mut host).members.len(), 2);
        let _back = connect(&mut hub, "g", "Guest");
        assert!(last_team(&mut host).member("g").unwrap().online);
        assert!(hub.disconnect("g", 1));
        hub.handle("h", ToServer::Ended).unwrap();
        assert_eq!(last_team(&mut host).members.len(), 1);
    }

    #[test]
    fn a_replaced_connection_does_not_drop_the_player() {
        let mut hub = Hub::default();
        let _first = connect(&mut hub, "h", "Host");
        let _second = connect(&mut hub, "h", "Host");
        assert!(!hub.disconnect("h", 0));
        assert_eq!(hub.owner("token-h"), Some("h"));
    }

    #[test]
    fn one_change_sends_one_snapshot() {
        let mut hub = Hub::default();
        let mut host = connect(&mut hub, "h", "Host");
        let team = code(&hub, "h");
        for (uuid, nick) in [("a", "Alpha"), ("b", "Bravo")] {
            let _ = connect(&mut hub, uuid, nick);
            hub.handle(uuid, ToServer::Join { code: team.clone() }).unwrap();
            hub.handle(uuid, ToServer::Presence { presence: Presence::Ready }).unwrap();
        }
        hub.handle("h", ToServer::Presence { presence: Presence::Menu }).unwrap();
        hub.handle("h", ToServer::Start).unwrap();
        assert!(hub.disconnect("a", 0));
        assert!(hub.disconnect("b", 0));
        let _ = last_team(&mut host);
        hub.handle("h", ToServer::Presence { presence: Presence::Menu }).unwrap();
        assert!(host.try_recv().is_err());
        hub.handle("h", ToServer::Ended).unwrap();
        assert_eq!(std::iter::from_fn(|| host.try_recv().ok()).count(), 1);
    }
}
