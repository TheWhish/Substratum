package xyz.thewhish.substratum.network

import io.netty.buffer.ByteBuf

object ReplayPosition {
    private const val FIXED_FIELDS = 33
    private const val VAR_INT_MORE = 0x80

    @JvmStatic
    fun lacksDimension(buf: ByteBuf): Boolean {
        var index = buf.readerIndex() + FIXED_FIELDS
        while (index < buf.writerIndex() && buf.getByte(index).toInt() and VAR_INT_MORE != 0) index++
        return index + 1 == buf.writerIndex()
    }
}
