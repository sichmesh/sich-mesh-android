// The phone link's chunks (sich-s3 docs/phone-link.md, section 4): the same rules as the device's
// src/phonelink_core.h. ChunksTest checks this code against chunks the device code made.
//
//   chunk = type (1) | id (1) | idx (2, big-endian, bit 15 = last) | data
package org.sichmesh.app.link

import java.io.ByteArrayOutputStream

object Chunks {
    const val HEADER = 4
    const val MAX_MESSAGE = 24 * 1024
    private const val LAST = 0x8000

    /** The chunks of one message for a link with this ATT MTU, or null (too long / MTU too small). */
    fun split(type: Int, id: Int, data: ByteArray, mtu: Int): List<ByteArray>? {
        if (mtu < 23 || data.size > MAX_MESSAGE) return null
        val room = minOf(mtu - 3 - HEADER, 512)
        val n = if (data.isEmpty()) 1 else (data.size + room - 1) / room
        if (n > 0x8000) return null
        return (0 until n).map { k ->
            val off = k * room
            val len = minOf(data.size - off, room)
            val x = k or (if (k + 1 == n) LAST else 0)
            ByteArray(HEADER + len).also { c ->
                c[0] = type.toByte(); c[1] = id.toByte()
                c[2] = (x shr 8).toByte(); c[3] = x.toByte()
                if (len > 0) System.arraycopy(data, off, c, HEADER, len)
            }
        }
    }
}

class Message(val type: Int, val id: Int, val data: ByteArray)

/** Puts messages back together. One message per type may be in progress. */
class Assembler {
    private class Part(val id: Int) { var next = 0; val data = ByteArrayOutputStream() }
    private val parts = HashMap<Int, Part>()

    /** One chunk in; a whole message out when its last chunk arrives. */
    fun feed(c: ByteArray): Message? {
        if (c.size < Chunks.HEADER) return null
        val type = c[0].toInt() and 0xFF
        val id = c[1].toInt() and 0xFF
        val x = ((c[2].toInt() and 0xFF) shl 8) or (c[3].toInt() and 0xFF)
        val idx = x and 0x7FFF
        val last = (x and 0x8000) != 0
        if (idx == 0) parts[type] = Part(id)
        val p = parts[type]
        if (p == null || p.id != id || p.next != idx) { parts.remove(type); return null }   // lost / out of order: drop it
        if (p.data.size() + c.size - Chunks.HEADER > Chunks.MAX_MESSAGE) { parts.remove(type); return null }
        p.data.write(c, Chunks.HEADER, c.size - Chunks.HEADER)
        p.next++
        if (!last) return null
        parts.remove(type)
        return Message(type, id, p.data.toByteArray())
    }

    /** A new connection: half messages are gone. */
    fun reset() = parts.clear()
}
