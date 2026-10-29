package com.aura.livewall.data

import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** LRU-кэш .glb в direct-памяти (общий для приложения и обоев, один процесс). */
object ModelCache {
    private const val BUDGET = 192L shl 20
    private val lru = LinkedHashMap<String, ByteBuffer>(8, 0.75f, true)
    private var bytes = 0L
    private val io = Executors.newSingleThreadExecutor()

    fun get(path: String): ByteBuffer {
        synchronized(this) { lru[path]?.let { return it.duplicate() } }
        val f = File(path)
        val buf = ByteBuffer.allocateDirect(f.length().toInt())
        FileInputStream(f).channel.use { ch -> while (buf.hasRemaining()) if (ch.read(buf) < 0) break }
        buf.flip()
        synchronized(this) {
            lru[path]?.let { return it.duplicate() }
            lru[path] = buf; bytes += buf.capacity()
            val it = lru.entries.iterator()
            while (bytes > BUDGET && lru.size > 1 && it.hasNext()) { bytes -= it.next().value.capacity(); it.remove() }
        }
        return buf.duplicate()
    }

    fun preload(paths: List<String>) = io.execute { paths.forEach { runCatching { get(it) } } }

    @Synchronized fun evict(path: String) { lru.remove(path)?.let { bytes -= it.capacity() } }
}
