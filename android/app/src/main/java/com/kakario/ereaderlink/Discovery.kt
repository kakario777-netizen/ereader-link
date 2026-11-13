package com.kakario.ereaderlink

import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import kotlin.concurrent.thread

/**
 * 같은 와이파이 안에서 PC가 이북리더를 자동으로 찾게 해 주는 부분.
 *  - PC가 "EREADER_LINK?"를 뿌리면 바로 대답
 *  - 2초마다 "나 여기 있어"를 스스로 뿌림 (어느 쪽이든 먼저 닿는 걸로 연결)
 */
class Discovery {
    @Volatile private var stop = false
    private var socket: DatagramSocket? = null

    private fun hello(): ByteArray {
        val j = JSONObject().put("name", FileServer.deviceName()).put("port", LinkState.HTTP_PORT)
        return ("EREADER_LINK!" + j).toByteArray(Charsets.UTF_8)
    }

    fun start() {
        thread(name = "discovery-listen", isDaemon = true) {
            while (!stop) {
                try {
                    val s = DatagramSocket(null).apply {
                        reuseAddress = true
                        broadcast = true
                        bind(InetSocketAddress(LinkState.DISCOVERY_PORT))
                    }
                    socket = s
                    val buf = ByteArray(512)
                    while (!stop) {
                        val pk = DatagramPacket(buf, buf.size)
                        s.receive(pk)
                        val msg = String(pk.data, 0, pk.length, Charsets.UTF_8)
                        if (msg.startsWith("EREADER_LINK?")) {
                            val out = hello()
                            s.send(DatagramPacket(out, out.size, pk.address, pk.port))
                        }
                    }
                } catch (_: Exception) {
                    if (!stop) Thread.sleep(1500)
                } finally {
                    runCatching { socket?.close() }
                }
            }
        }
        thread(name = "discovery-announce", isDaemon = true) {
            while (!stop) {
                runCatching {
                    DatagramSocket().use { s ->
                        s.broadcast = true
                        val out = hello()
                        for (addr in broadcastAddresses()) {
                            runCatching { s.send(DatagramPacket(out, out.size, addr, LinkState.ANNOUNCE_PORT)) }
                        }
                    }
                }
                // 이미 PC와 연결돼 있으면 덜 자주 알림
                Thread.sleep(if (LinkState.connected) 6000 else 2000)
            }
        }
    }

    fun close() {
        stop = true
        runCatching { socket?.close() }
    }

    companion object {
        fun broadcastAddresses(): List<InetAddress> {
            val list = mutableListOf<InetAddress>(InetAddress.getByName("255.255.255.255"))
            runCatching {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) ia.broadcast?.let { list += it }
                }
            }
            return list.distinct()
        }

        /** 화면에 보여줄 이 기기의 와이파이 IP */
        fun localIps(): List<String> {
            val out = mutableListOf<String>()
            runCatching {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) {
                        val a = ia.address
                        if (a is java.net.Inet4Address && !a.isLoopbackAddress) out += a.hostAddress ?: continue
                    }
                }
            }
            return out
        }
    }
}
