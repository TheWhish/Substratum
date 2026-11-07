package xyz.thewhish.substratum.client

import com.google.gson.Gson
import com.google.gson.JsonParseException
import xyz.thewhish.substratum.Substratum
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.Writer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

object Link {
    class Member(
        val uuid: String = "",
        val nick: String = "",
        val skin: String = "",
        val build: String = "",
        val presence: String = AWAY,
        val online: Boolean = false,
    )

    class Session(val participants: List<String> = emptyList(), val open: Boolean = false)

    class Team(
        val code: String = "",
        val host: String = "",
        val members: List<Member> = emptyList(),
        val session: Session? = null,
    )

    class State(val me: String = "", val team: Team? = null, val door: Int? = null)

    private class Incoming(
        val type: String = "",
        val me: String = "",
        val team: Team? = null,
        val door: Int? = null,
        val reason: String? = null,
    )

    const val AWAY = "away"
    const val MENU = "menu"
    const val READY = "ready"
    const val PLAYING = "playing"
    private const val GREETING_TIMEOUT = 5000

    private val gson = Gson()
    private var writer: Writer? = null

    @Volatile
    var state: State? = null
        private set

    @Volatile
    var refusal: String? = null

    @Volatile
    var changes = 0
        private set

    fun start(): Boolean {
        val port = System.getProperty("substratum.link")?.toIntOrNull() ?: return false
        val key = System.getProperty("substratum.key") ?: return false
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), GREETING_TIMEOUT)
            socket.tcpNoDelay = true
            socket.soTimeout = GREETING_TIMEOUT
            val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            writer.write(gson.toJson(mapOf("type" to "hello", "key" to key)))
            writer.write("\n")
            writer.flush()
            receive(reader.readLine() ?: throw IOException("The launcher refused the link"))
            socket.soTimeout = 0
            synchronized(this) { this.writer = writer }
            thread(isDaemon = true, name = "substratum-link") { listen(socket, reader) }
            return true
        } catch (error: IOException) {
            Substratum.LOGGER.error("Failed to reach the launcher", error)
            socket.close()
            return false
        }
    }

    fun presence(value: String) = send(mapOf("type" to "presence", "presence" to value))

    fun requestStart() = send(mapOf("type" to "start"))

    fun opened(port: Int) = send(mapOf("type" to "opened", "port" to port))

    fun ended() = send(mapOf("type" to "ended"))

    fun hostedSession(): Session? {
        val state = state ?: return null
        val team = state.team ?: return null
        return team.session.takeIf { team.host == state.me }
    }

    private fun listen(socket: Socket, reader: BufferedReader) {
        try {
            socket.use { while (true) receive(reader.readLine() ?: break) }
        } catch (error: IOException) {
            Substratum.LOGGER.error("Lost the link to the launcher", error)
        } finally {
            synchronized(this) { writer = null }
            state = null
            changes++
        }
    }

    private fun receive(line: String) {
        val message = try {
            gson.fromJson(line, Incoming::class.java)
        } catch (error: JsonParseException) {
            Substratum.LOGGER.error("Unreadable message from the launcher: {}", line, error)
            return
        }
        when (message.type) {
            "state" -> state = State(message.me, message.team, message.door)
            "refused" -> refusal = message.reason
            else -> return
        }
        changes++
    }

    private fun send(message: Map<String, Any>) {
        synchronized(this) {
            val writer = writer ?: return
            try {
                writer.write(gson.toJson(message))
                writer.write("\n")
                writer.flush()
            } catch (error: IOException) {
                Substratum.LOGGER.warn("Failed to tell the launcher {}", message, error)
            }
        }
    }
}
