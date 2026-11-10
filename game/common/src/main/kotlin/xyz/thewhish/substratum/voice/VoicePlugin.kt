package xyz.thewhish.substratum.voice

import de.maxhenkel.voicechat.api.ClientVoicechatSocket
import de.maxhenkel.voicechat.api.RawUdpPacket
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatSocket
import de.maxhenkel.voicechat.api.events.ClientVoicechatInitializationEvent
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.VoicechatServerStartingEvent
import dev.architectury.platform.Platform
import dev.architectury.utils.Env
import xyz.thewhish.substratum.Substratum
import xyz.thewhish.substratum.client.Link
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress

object VoicePlugin : VoicechatPlugin {

    override fun getPluginId() = Substratum.ID

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(VoicechatServerStartingEvent::class.java) { event ->
            if (Platform.getEnvironment() == Env.CLIENT) event.socketImplementation = HostSocket()
        }
        registration.registerEvent(ClientVoicechatInitializationEvent::class.java) { event ->
            event.socketImplementation = GuestSocket()
        }
    }
}

private const val PACKET_MAX = 4096

private class HostSocket : VoicechatSocket {
    private var socket: DatagramSocket? = null
    private val buffer = ByteArray(PACKET_MAX)

    override fun open(port: Int, bindAddress: String) {
        check(socket == null) { "The voice socket is already open" }
        val opened = DatagramSocket(port, InetAddress.getLoopbackAddress())
        socket = opened
        Link.voice(opened.localPort)
    }

    override fun read(): RawUdpPacket = socket.receive(buffer)

    override fun send(data: ByteArray, address: SocketAddress) = socket.opened().send(DatagramPacket(data, data.size, address))

    override fun getLocalPort() = socket?.localPort ?: -1

    override fun close() {
        socket?.close()
        Link.voice(null)
    }

    override fun isClosed() = socket?.isClosed ?: true
}

private class GuestSocket : ClientVoicechatSocket {
    private var socket: DatagramSocket? = null
    private val buffer = ByteArray(PACKET_MAX)

    override fun open() {
        check(socket == null) { "The voice socket is already open" }
        socket = DatagramSocket()
    }

    override fun read(): RawUdpPacket = socket.receive(buffer)

    override fun send(data: ByteArray, address: SocketAddress) {
        val tunnel = Link.state?.voice?.let { InetSocketAddress(InetAddress.getLoopbackAddress(), it) }
        socket.opened().send(DatagramPacket(data, data.size, tunnel ?: address))
    }

    override fun close() {
        socket?.close()
    }

    override fun isClosed() = socket?.isClosed ?: true
}

private fun DatagramSocket?.opened() = this ?: throw IllegalStateException("The voice socket is not open")

private fun DatagramSocket?.receive(buffer: ByteArray): RawUdpPacket {
    val packet = DatagramPacket(buffer, buffer.size)
    opened().receive(packet)
    return Received(buffer.copyOf(packet.length), System.currentTimeMillis(), packet.socketAddress)
}

private class Received(private val bytes: ByteArray, private val time: Long, private val from: SocketAddress) : RawUdpPacket {
    override fun getData() = bytes
    override fun getTimestamp() = time
    override fun getSocketAddress() = from
}
