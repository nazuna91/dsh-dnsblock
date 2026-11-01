package com.dsh.dnsblock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * 极简 DNS 拦截 VPN。
 *
 * 原理：把系统 DNS 指向本机 tun 地址，读到 UDP/53 请求后：
 *   命中黑名单 → 直接构造 A 记录 0.0.0.0 的应答
 *   其它       → 转发给上游 223.5.5.5，把应答写回 tun
 *
 * 只处理 IPv4 + UDP，够用。
 */
class DnsVpnService : VpnService() {

    companion object {
        const val ACTION_START = "com.dsh.dnsblock.START"
        const val ACTION_STOP = "com.dsh.dnsblock.STOP"
        const val CHANNEL = "dnsblock"
        const val NOTIFY_ID = 42
        const val VPN_ADDR = "10.111.222.1"
        const val UPSTREAM = "223.5.5.5"
        val blockedCount = AtomicLong(0)
        val totalCount = AtomicLong(0)
    }

    private var tun: ParcelFileDescriptor? = null
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        startForeground(NOTIFY_ID, buildNotification())
        if (worker == null) {
            val fd = establish()
            if (fd == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            tun = fd
            worker = Thread { loop(fd) }.also { it.start() }
        }
        return START_STICKY
    }

    private fun establish(): ParcelFileDescriptor? {
        return try {
            Builder()
                .setSession("DSH DNS Block")
                .addAddress(VPN_ADDR, 32)
                .addDnsServer(VPN_ADDR)
                .addRoute(VPN_ADDR, 32)
                .setBlocking(true)
                .setMtu(1500)
                .establish()
        } catch (e: Throwable) {
            null
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "DNS 拦截", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("广告拦截运行中")
            .setContentText("已拦 ${blockedCount.get()} / 总 ${totalCount.get()}")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    /** tun 上读 IP 包 → 取 UDP/53 → 应答 → 写回 */
    private fun loop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteArray(4096)
        val upstream: DatagramSocket? = try {
            DatagramSocket().apply { soTimeout = 3000 }
        } catch (e: Throwable) {
            null
        }

        while (!Thread.currentThread().isInterrupted) {
            val n = try { input.read(buf) } catch (e: Throwable) { break }
            if (n <= 0) continue
            val packet = buf.copyOf(n)
            try {
                val p = Ipv4Udp.parse(packet) ?: continue
                totalCount.incrementAndGet()
                val host = DnsQuery.qname(p.payload) ?: continue

                val reply: ByteArray? = if (Blocklist.hit(host)) {
                    blockedCount.incrementAndGet()
                    DnsQuery.blocked(p.payload)
                } else {
                    upstream?.let { forward(it, p.payload) }
                }
                if (reply != null) {
                    val out = Ipv4Udp.build(p, reply)
                    output.write(out)
                }
            } catch (e: Throwable) {
                // 单个包失败不影响整体
            }
        }

        try { upstream?.close() } catch (_: Throwable) {}
    }

    private fun forward(sock: DatagramSocket, query: ByteArray): ByteArray? {
        return try {
            val addr = InetAddress.getByName(UPSTREAM)
            sock.send(DatagramPacket(query, query.size, addr, 53))
            val resp = ByteArray(4096)
            val pkt = DatagramPacket(resp, resp.size)
            sock.receive(pkt)
            resp.copyOf(pkt.length)
        } catch (e: Throwable) {
            null
        }
    }

    private fun stop() {
        worker?.interrupt()
        worker = null
        try { tun?.close() } catch (_: Throwable) {}
        tun = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stop()
        super.onRevoke()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }
}

/** IPv4 + UDP 头解析/构造（只做必要字段） */
class Ipv4Udp(
    val srcIp: ByteArray,
    val dstIp: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
    val ipHeaderLen: Int,
) {
    companion object {
        fun parse(p: ByteArray): Ipv4Udp? {
            if (p.size < 28) return null
            val version = (p[0].toInt() shr 4) and 0xF
            if (version != 4) return null
            val ihl = (p[0].toInt() and 0xF) * 4
            if (p.size < ihl + 8) return null
            val proto = p[9].toInt() and 0xFF
            if (proto != 17) return null // UDP
            val totalLen = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
            val src = p.copyOfRange(12, 16)
            val dst = p.copyOfRange(16, 20)
            val sp = ((p[ihl].toInt() and 0xFF) shl 8) or (p[ihl + 1].toInt() and 0xFF)
            val dp = ((p[ihl + 2].toInt() and 0xFF) shl 8) or (p[ihl + 3].toInt() and 0xFF)
            val end = minOf(totalLen, p.size)
            val udpLen = ((p[ihl + 4].toInt() and 0xFF) shl 8) or (p[ihl + 5].toInt() and 0xFF)
            val payloadEnd = minOf(ihl + udpLen, end)
            if (payloadEnd <= ihl + 8) return null
            return Ipv4Udp(src, dst, sp, dp, p.copyOfRange(ihl + 8, payloadEnd), ihl)
        }

        fun build(parsed: Ipv4Udp, payload: ByteArray): ByteArray {
            val udpLen = 8 + payload.size
            val ipLen = 20 + udpLen
            val b = ByteBuffer.allocate(ipLen)
            b.put(0x45)
            b.put(0)
            b.putShort(ipLen.toShort())
            b.putShort(0)
            b.putShort(0)
            b.put(64)
            b.put(17)
            b.putShort(0)
            b.put(parsed.dstIp)   // 源/目的对调
            b.put(parsed.srcIp)
            b.putShort(parsed.dstPort.toShort())
            b.putShort(parsed.srcPort.toShort())
            b.putShort(udpLen.toShort())
            b.putShort(0)
            b.put(payload)
            val arr = b.array()
            // IPv4 头校验和
            var sum = 0
            for (i in 0 until 20 step 2) {
                sum += ((arr[i].toInt() and 0xFF) shl 8) or (arr[i + 1].toInt() and 0xFF)
                sum = (sum and 0xFFFF) + (sum shr 16)
            }
            val check = (sum.inv() and 0xFFFF).toShort()
            arr[10] = (check.toInt() shr 8).toByte()
            arr[11] = check.toByte()
            return arr
        }
    }
}

/** 最小 DNS 处理：取 QNAME、构造 0.0.0.0 应答 */
class DnsQuery {
    companion object {
        fun qname(dns: ByteArray): String? {
            if (dns.size < 12) return null
            var i = 12
            val sb = StringBuilder()
            var guard = 0
            while (i < dns.size && guard < 128) {
                val len = dns[i].toInt() and 0xFF
                if (len == 0) break
                if (len > 63) return null
                if (i + 1 + len > dns.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(dns, i + 1, len, Charsets.US_ASCII))
                i += 1 + len
                guard++
            }
            return if (sb.isEmpty()) null else sb.toString()
        }

        /** 复制请求头 + 问题段，追加一条 A 记录 0.0.0.0 */
        fun blocked(query: ByteArray): ByteArray? {
            if (query.size < 12) return null
            // 找到问题段结束位置
            var i = 12
            while (i < query.size && query[i].toInt() != 0) {
                if ((query[i].toInt() and 0xFF) > 63) return null
                i += 1 + (query[i].toInt() and 0xFF)
            }
            val qEnd = i + 5 // 0x00 + qtype(2) + qclass(2)
            if (qEnd > query.size) return null
            val out = ByteArray(qEnd + 16)
            System.arraycopy(query, 0, out, 0, qEnd)
            // flags: 标准响应 + 递归可用 + 无错误
            out[2] = 0x81.toByte(); out[3] = 0x80.toByte()
            // QDCOUNT = 1, ANCOUNT = 1
            out[4] = 0; out[5] = 1
            out[6] = 0; out[7] = 1
            out[8] = 0; out[9] = 0
            out[10] = 0; out[11] = 0
            var p = qEnd
            out[p++] = 0xC0.toByte(); out[p++] = 0x0C.toByte() // 指向 QNAME
            out[p++] = 0; out[p++] = 1                        // TYPE A
            out[p++] = 0; out[p++] = 1                        // CLASS IN
            out[p++] = 0; out[p++] = 0; out[p++] = 0; out[p++] = 60 // TTL 60
            out[p++] = 0; out[p++] = 4                        // RDLENGTH
            out[p++] = 0; out[p++] = 0; out[p++] = 0; out[p] = 0 // 0.0.0.0
            return out
        }
    }
}
