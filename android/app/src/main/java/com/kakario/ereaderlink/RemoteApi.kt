package com.kakario.ereaderlink

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** 폰에서 다른 기기(이북리더)의 이북 링크 앱에 접속하는 부분 — PC 프로그램과 같은 방식 */
class RemoteApi(val host: String, val port: Int) {
    data class Root(val label: String, val path: String, val free: Long)
    data class Item(val name: String, val path: String, val dir: Boolean, val size: Long, val mtime: Long)
    class Info(val name: String, val allAccess: Boolean, val roots: List<Root>)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(method: String, pathAndQuery: String, timeout: Int): HttpURLConnection {
        val c = URL("http://$host:$port$pathAndQuery").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5000
        c.readTimeout = timeout
        c.useCaches = false
        return c
    }

    private fun check(c: HttpURLConnection) {
        val code = c.responseCode
        if (code !in 200..299) {
            val msg = runCatching { c.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            throw IOException(msg?.takeIf { it.isNotBlank() } ?: "응답 $code")
        }
    }

    private fun call(method: String, pq: String, timeout: Int = 15000): String {
        val c = open(method, pq, timeout)
        try {
            if (method != "GET") {
                c.doOutput = true
                c.setFixedLengthStreamingMode(0)
                c.outputStream.close()
            }
            check(c)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun info(): Info {
        val j = JSONObject(call("GET", "/info", 5000))
        val arr = j.getJSONArray("roots")
        val roots = (0 until arr.length()).map {
            val r = arr.getJSONObject(it)
            Root(r.getString("label"), r.getString("path"), r.optLong("free"))
        }
        return Info(j.optString("name", "이북리더"), j.optBoolean("allAccess", true), roots)
    }

    fun list(dir: String): List<Item> {
        val arr = JSONObject(call("GET", "/list?p=" + enc(dir))).getJSONArray("items")
        return (0 until arr.length()).map {
            val r = arr.getJSONObject(it)
            val n = r.getString("n")
            Item(n, join(dir, n), r.optBoolean("d"), r.optLong("s"), r.optLong("m"))
        }
    }

    fun mkdir(p: String) { call("POST", "/mkdir?p=" + enc(p)) }
    fun delete(p: String) { call("POST", "/delete?p=" + enc(p), 600_000) }
    fun rename(p: String, to: String) { call("POST", "/rename?p=" + enc(p) + "&to=" + enc(to), 600_000) }
    fun copy(p: String, to: String) { call("POST", "/copy?p=" + enc(p) + "&to=" + enc(to), 600_000) }

    fun download(p: String, out: OutputStream, onBytes: (Int) -> Unit, cancel: AtomicBoolean) {
        val c = open("GET", "/file?p=" + enc(p), 30_000)
        try {
            check(c)
            c.inputStream.use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    if (cancel.get()) throw IOException("취소했어요")
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    onBytes(n)
                }
            }
        } finally {
            c.disconnect()
        }
    }

    fun upload(remote: String, input: InputStream, length: Long, mtime: Long, onBytes: (Int) -> Unit, cancel: AtomicBoolean) {
        val q = "/file?p=" + enc(remote) + (if (mtime > 0) "&m=$mtime" else "")
        val c = open("PUT", q, 60_000)
        try {
            c.doOutput = true
            c.setFixedLengthStreamingMode(length)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.outputStream.use { out ->
                val buf = ByteArray(256 * 1024)
                var left = length
                while (left > 0) {
                    if (cancel.get()) throw IOException("취소했어요")
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("파일을 끝까지 읽지 못했어요")
                    out.write(buf, 0, n)
                    left -= n
                    onBytes(n)
                }
            }
            check(c)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        fun join(dir: String, name: String) = dir.trimEnd('/') + "/" + name
        fun parent(p: String): String { val t = p.trimEnd('/'); val i = t.lastIndexOf('/'); return if (i <= 0) "/" else t.substring(0, i) }
        fun name(p: String): String = p.trimEnd('/').substringAfterLast('/')
    }
}

/** 같은 와이파이에서 다른 이북 링크 기기 찾기 (자기 자신은 뺌) */
class DeviceFinder(private val onFound: (name: String, host: String, port: Int) -> Unit) {
    @Volatile private var stop = false

    fun start() {
        thread(name = "finder", isDaemon = true) {
            val self = Discovery.localIps().toSet()
            val msg = "EREADER_LINK?".toByteArray()
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.soTimeout = 500
                    var lastSend = 0L
                    val buf = ByteArray(1024)
                    while (!stop) {
                        if (System.currentTimeMillis() - lastSend > 2000) {
                            lastSend = System.currentTimeMillis()
                            for (a in Discovery.broadcastAddresses()) {
                                runCatching { s.send(DatagramPacket(msg, msg.size, a, LinkState.DISCOVERY_PORT)) }
                            }
                        }
                        try {
                            val pk = DatagramPacket(buf, buf.size)
                            s.receive(pk)
                            val t = String(pk.data, 0, pk.length, Charsets.UTF_8)
                            val host = pk.address.hostAddress ?: continue
                            if (!t.startsWith("EREADER_LINK!") || host in self) continue
                            val j = JSONObject(t.substring(13))
                            onFound(j.optString("name", "기기"), host, j.optInt("port", LinkState.HTTP_PORT))
                        } catch (_: SocketTimeoutException) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() { stop = true }
}
