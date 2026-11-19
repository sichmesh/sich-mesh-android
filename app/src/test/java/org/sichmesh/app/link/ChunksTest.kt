// The app's chunk code against chunks the DEVICE code made (vectors.txt from sich-s3 tools/phonelink_test).
package org.sichmesh.app.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class ChunksTest {
    private class Vec(val type: Int, val id: Int, val mtu: Int, val data: ByteArray, val chunks: List<String>?)

    private fun unhex(h: String): ByteArray = if (h == "-") ByteArray(0) else ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun vectors(): List<Vec> {
        val text = javaClass.classLoader!!.getResourceAsStream("vectors.txt")!!.bufferedReader().readText()
        return text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.map { l ->
            val f = l.split(" ")
            Vec(f[0].toInt(), f[1].toInt(), f[2].toInt(), unhex(f[3]), if (f[4] == "REFUSED") null else f[4].split(","))
        }
    }

    @Test fun sameChunksAsTheDevice() {
        for (v in vectors()) {
            val got = Chunks.split(v.type, v.id, v.data, v.mtu)
            if (v.chunks == null) assertNull("refused by the device", got)
            else assertEquals("type ${v.type} mtu ${v.mtu} ${v.data.size} bytes", v.chunks, got!!.map { hex(it) })
        }
    }

    @Test fun putsTheDeviceChunksBackTogether() {
        val a = Assembler()
        for (v in vectors()) {
            val chunks = v.chunks ?: continue
            var m: Message? = null
            for (c in chunks) m = a.feed(unhex(c)) ?: m
            assertNotNull(m)
            assertEquals(v.type, m!!.type); assertEquals(v.id, m.id); assertEquals(hex(v.data), hex(m.data))
        }
    }

    @Test fun lostOrSwappedChunkDropsTheMessageAndTypesInterleave() {
        val r = Random(1)
        val c = Chunks.split(Proto.API, 5, r.nextBytes(900), 100)!!
        var a = Assembler()
        assertEquals(0, (c.take(2) + c.drop(3)).mapNotNull { a.feed(it) }.size)
        a = Assembler()
        assertEquals(0, (listOf(c[0], c[2], c[1]) + c.drop(3)).mapNotNull { a.feed(it) }.size)
        a = Assembler()
        val e = Chunks.split(Proto.HELLO, 9, "{\"app\":\"sichmesh\"}".toByteArray(), 23)!!
        val got = mutableListOf<Message>()
        for (i in 0 until maxOf(c.size, e.size)) {
            if (i < c.size) a.feed(c[i])?.let { got.add(it) }
            if (i < e.size) a.feed(e[i])?.let { got.add(it) }
        }
        assertEquals(2, got.size)
    }
}
