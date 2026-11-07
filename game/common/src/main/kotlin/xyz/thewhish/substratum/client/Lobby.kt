package xyz.thewhish.substratum.client

import com.mojang.util.UndashedUuid
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component
import net.minecraft.util.HttpUtil
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.menu.Loading
import xyz.thewhish.substratum.client.menu.MainMenu
import xyz.thewhish.substratum.client.menu.WaitScreen
import java.util.UUID

object Lobby {
    private const val LOOPBACK = "127.0.0.1"
    private const val NOT_READY = "not-ready"

    private var seen = -1
    private var pending: (() -> Unit)? = null
    private var joining = false
    private var started = false
    private var reported: String? = null
    private var inWorld = false

    var ready = false
        private set

    var gated = false
        private set

    var note: Component? = null

    val team: Link.Team? get() = Link.state?.team

    private val me: String get() = Link.state?.me.orEmpty()

    val guest: Boolean get() = team?.let { it.host != me } ?: false

    val session: Link.Session? get() = team?.session

    val participant: Boolean get() = session?.participants?.contains(me) ?: false

    fun readiness(): Pair<Int, Int> {
        val team = team ?: return 0 to 0
        val others = team.members.filter { it.uuid != team.host }
        val host = team.members.find { it.uuid == team.host }
        return others.count { it.online && it.presence == Link.READY && it.build == host?.build } to others.size
    }

    fun everyoneReady(): Boolean = readiness().let { (ready, total) -> ready == total }

    fun sameVersion(): Boolean {
        val team = team ?: return true
        val host = team.members.find { it.uuid == team.host }
        return team.members.find { it.uuid == me }?.build == host?.build
    }

    fun canReturn(): Boolean = guest && participant && session?.open == true

    fun skinOf(id: UUID): String? {
        val uuid = UndashedUuid.toString(id)
        return team?.members?.find { it.uuid == uuid }?.skin
    }

    fun launch(action: () -> Unit) {
        note = null
        if (team == null) {
            action()
            return
        }
        pending = action
        Link.requestStart()
    }

    fun toggleReady() {
        ready = !ready
        inMenu()
    }

    fun inMenu() = report(if (ready) Link.READY else Link.MENU)

    fun rejoin(minecraft: Minecraft) {
        note = null
        wait(minecraft)
    }

    fun entered(minecraft: Minecraft) {
        inWorld = true
        ready = false
        report(Link.PLAYING)
        val session = session ?: return
        gated = session.participants.size > 1
        if (!guest && gated && !session.open) open(minecraft)
    }

    fun left() {
        if (!inWorld) return
        inWorld = false
        gated = false
        if (Link.hostedSession() != null) Link.ended()
    }

    fun release(minecraft: Minecraft) {
        gated = false
        if (minecraft.screen is WaitScreen && minecraft.level != null) minecraft.setScreen(null)
    }

    fun disconnectNote(): Component =
        Component.translatable(if (session == null) "menu.substratum.host_ended" else "menu.substratum.host_lost")

    fun tick(minecraft: Minecraft) {
        if (Link.changes != seen) {
            seen = Link.changes
            changed(minecraft)
        }
        val state = Link.state ?: return
        val session = state.team?.session ?: return
        val door = state.door
        if (joining && session.open && door != null && minecraft.level == null) {
            joining = false
            connect(minecraft, door)
        }
        val action = pending
        if (action != null && state.team.host == state.me) {
            pending = null
            action()
        }
    }

    private fun changed(minecraft: Minecraft) {
        Link.refusal?.let { reason ->
            Link.refusal = null
            if (pending != null) {
                pending = null
                note = Component.translatable(if (reason == NOT_READY) "menu.substratum.not_ready" else "menu.substratum.refused")
            }
        }
        val running = session != null
        val waiting = minecraft.screen is WaitScreen && minecraft.level == null
        if (!running && started && minecraft.level != null && !minecraft.hasSingleplayerServer()) {
            leaveEndedGame(minecraft)
        }
        if (running && !started && guest && participant && minecraft.level == null) wait(minecraft)
        if (!running && waiting) {
            joining = false
            minecraft.setScreen(MainMenu(Component.translatable("menu.substratum.host_ended")))
        }
        if (Link.state == null) {
            pending = null
            reported = null
            if (waiting) minecraft.setScreen(MainMenu())
        } else if (reported == null && minecraft.level == null) {
            inMenu()
        }
        started = running
        (minecraft.screen as? MainMenu)?.refresh()
    }

    private fun leaveEndedGame(minecraft: Minecraft) {
        minecraft.level?.disconnect()
        minecraft.disconnect()
        minecraft.setScreen(MainMenu(Component.translatable("menu.substratum.host_ended")))
    }

    private fun report(presence: String) {
        if (Link.state == null || presence == reported) return
        reported = presence
        Link.presence(presence)
    }

    private fun wait(minecraft: Minecraft) {
        joining = true
        Loading.begin()
        minecraft.setScreen(WaitScreen())
    }

    private fun connect(minecraft: Minecraft, door: Int) {
        val server = ServerData(team?.code.orEmpty(), "$LOOPBACK:$door", ServerData.Type.OTHER)
        ConnectScreen.startConnecting(MainMenu(), minecraft, ServerAddress(LOOPBACK, door), server, false, null)
    }

    private fun open(minecraft: Minecraft) {
        val server = minecraft.singleplayerServer ?: return
        val port = HttpUtil.getAvailablePort()
        server.setUsesAuthentication(false)
        if (server.publishServer(null, false, port)) {
            Link.opened(port)
        } else {
            Substratum.LOGGER.error("Failed to open the world to the team on port {}", port)
            Link.ended()
        }
    }
}
