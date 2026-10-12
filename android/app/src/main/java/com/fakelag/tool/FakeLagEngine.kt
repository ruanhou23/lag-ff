package com.fakelag.tool

import android.content.Context
import android.media.AudioAttributes
import android.media.ToneGenerator
import android.net.VpnService
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * Android equivalent of Python's FakeLagEngine.
 *
 * Python (PC):
 *   WinDivert filter: "inbound and udp"
 *   Drop packets with raw length 50-250 bytes (IP hdr + UDP hdr + payload)
 *   Forward everything else (TCP untouched, DNS untouched implicitly via size filter)
 *
 * Android:
 *   Root mode:   iptables DROP rules (kernel-level, like WinDivert)
 *   VPN mode:    VpnService tunnel — forward TCP transparently, filter UDP inbound
 *
 * KEY FIXES from original (v1 → v2):
 *   1. TCP: proper state machine — handle pure ACK, FIN-ACK, SYN-ACK from device
 *   2. TCP: per-connection socket reader (no global poll loop)
 *   3. TCP: NO nested coroutines per packet — process inline
 *   4. IPv6 support in VPN mode
 *   5. Single shared synchronized FileOutputStream for TUN writes
 *   6. Size range 50-250 matches Python exactly
 *   7. UDP channel periodic cleanup (stale channels)
 */
class FakeLagEngine(private val context: Context) {

    companion object {
        @Volatile var isRunning = false
            private set

        private const val VPN_MTU = 1500

        fun randSeq() = ThreadLocalRandom.current().nextInt().toLong() and 0xFFFFFFFFL
    }

    // Instance callbacks (matches Python engine.error_callback pattern)
    var onStateChanged: ((Boolean) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    // Backend state
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnOutput: FileOutputStream? = null  // Shared TUN output (synchronized)
    private var scope: CoroutineScope? = null
    private val udpChannels = ConcurrentHashMap<String, DatagramChannel>()
    private val tcpSockets = ConcurrentHashMap<String, TcpConn>()
    private var useIptables = false

    // Packet range settings — NOW MATCHES PYTHON: 50-250 (was 70-250)
    var minByte = 50    // Python: 50 <= pkt_len
    var maxByte = 250   // Python: pkt_len <= 250

    data class TcpConn(
        val srcAddr: ByteArray, val srcPort: Int,
        val dstAddr: ByteArray, val dstPort: Int,
        val socket: Socket,
        var ourSeq: Long = randSeq(),
        var theirSeq: Long = 0,
        var sent: Long = 1   // SYN consumed 1 (matches Python's implicit SYN count)
    )

    // ─── start / stop (matches Python FakeLagEngine) ──────────────────
    fun start() {
        if (isRunning) return

        if (RootManager.hasRoot) {
            useIptables = true
            startIptables()
        } else {
            useIptables = false
            startVpn()
        }
    }

    fun stop() {
        if (useIptables) stopIptables() else stopVpn()
    }

    // ─── iptables path (kernel-level, like WinDivert) ─────────────────
    private fun startIptables() {
        try {
            // ---- IPv4 ----
            // Match Python: inbound UDP, total IP packet length 50-250 bytes
            // Note: iptables -m length matches TOTAL IP packet length (hdr+payload),
            // which is exactly what Python's len(packet.raw) returns.
            // DNS excluded (port 53 either direction).
            RootManager.execRoot("iptables -t filter -N FAKE_LAG_CHAIN 2>/dev/null")
            RootManager.execRoot("iptables -t filter -D INPUT -p udp -j FAKE_LAG_CHAIN 2>/dev/null")
            RootManager.execRoot("iptables -t filter -A INPUT -p udp -j FAKE_LAG_CHAIN")
            RootManager.execRoot("iptables -t filter -F FAKE_LAG_CHAIN 2>/dev/null")
            RootManager.execRoot(
                "iptables -t filter -A FAKE_LAG_CHAIN -p udp ! --sport 53 ! --dport 53 -m length --length $minByte:$maxByte -j DROP"
            )

            // ---- IPv6 ----
            RootManager.execRoot("ip6tables -t filter -N FAKE_LAG_CHAIN 2>/dev/null")
            RootManager.execRoot("ip6tables -t filter -D INPUT -p udp -j FAKE_LAG_CHAIN 2>/dev/null")
            RootManager.execRoot("ip6tables -t filter -A INPUT -p udp -j FAKE_LAG_CHAIN")
            RootManager.execRoot("ip6tables -t filter -F FAKE_LAG_CHAIN 2>/dev/null")
            RootManager.execRoot(
                "ip6tables -t filter -A FAKE_LAG_CHAIN -p udp ! --sport 53 ! --dport 53 -m length --length $minByte:$maxByte -j DROP"
            )

            isRunning = true
            onStateChanged?.invoke(true)
            beep(600, 250)

            // Monitor — keep rules alive (like Python's thread)
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope?.launch {
                while (isActive && isRunning) {
                    val ok = RootManager.execRootWithOutput(
                        "iptables -t filter -L FAKE_LAG_CHAIN 2>/dev/null | grep -c DROP"
                    ).trim()
                    if (ok == "0") {
                        RootManager.execRoot(
                            "iptables -t filter -A FAKE_LAG_CHAIN -p udp ! --sport 53 ! --dport 53 -m length --length $minByte:$maxByte -j DROP"
                        )
                        RootManager.execRoot(
                            "ip6tables -t filter -A FAKE_LAG_CHAIN -p udp ! --sport 53 ! --dport 53 -m length --length $minByte:$maxByte -j DROP"
                        )
                    }
                    delay(5000)
                }
            }
        } catch (e: Exception) {
            onError?.invoke("iptables failed: ${e.message}")
            isRunning = false
            onStateChanged?.invoke(false)
        }
    }

    private fun stopIptables() {
        isRunning = false
        scope?.cancel(); scope = null
        try {
            // Cleanup IPv4
            RootManager.execRoot("iptables -t filter -D INPUT -p udp -j FAKE_LAG_CHAIN 2>/dev/null")
            RootManager.execRoot("iptables -t filter -F FAKE_LAG_CHAIN 2>/dev/null")
            RootManager.execRoot("iptables -t filter -X FAKE_LAG_CHAIN 2>/dev/null")
            // Cleanup IPv6
            RootManager.execRoot("ip6tables -t filter -D INPUT -p udp -j FAKE_LAG_CHAIN 2>/dev/null")
            RootManager.execRoot("ip6tables -t filter -F FAKE_LAG_CHAIN 2>/dev/null")
            RootManager.execRoot("ip6tables -t filter -X FAKE_LAG_CHAIN 2>/dev/null")
        } catch (_: Exception) {}
        onStateChanged?.invoke(false)
        beep(400, 250)
    }

    // ─── VPN path (user-level fallback) ───────────────────────────────
    private fun startVpn() {
        try {
            val vpnIntent = VpnService.prepare(context)
            if (vpnIntent != null) {
                onError?.invoke("Cần cấp quyền VPN trước")
                return
            }
            onError?.invoke("VPN needs VpnService — use MainActivity to start")
        } catch (e: Exception) {
            onError?.invoke("VPN error: ${e.message}")
        }
    }

    // Called by VpnProxyService (has VpnService context)
    fun establishVpn(vpnService: VpnService): Boolean {
        if (isRunning) return false
        try {
            val builder = vpnService.Builder()
                .setSession("Fake Lag Tool")
                .addAddress("10.8.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setMtu(VPN_MTU)
                .setBlocking(true)

            // Exclude our own app + system apps from VPN
            // so system services don't hang (calls, notifications, Play Store, etc.)
            try {
                builder.addDisallowedApplication("com.fakelag.tool")
                val systemApps = arrayOf(
                    "com.android.systemui",
                    "com.google.android.gms",
                    "com.android.vending",
                    "com.google.android.googlequicksearchbox",
                    "com.google.android.apps.maps",
                    "com.android.chrome",
                    "android"
                )
                for (pkg in systemApps) {
                    try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            vpnInterface = builder.establish() ?: return false
            vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)

            isRunning = true
            useIptables = false
            onStateChanged?.invoke(true)
            beep(600, 250)

            scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            scope?.launch { vpnMainLoop(vpnService) }
            scope?.launch { vpnUdpReader() }
            // TCP socket readers are spawned per-connection in vpnHandleTcp

            // Periodic cleanup of stale UDP channels
            scope?.launch { udpCleaner() }

            return true
        } catch (e: Exception) {
            onError?.invoke("VPN establish: ${e.message}")
            return false
        }
    }

    private fun stopVpn() {
        isRunning = false
        scope?.cancel(); scope = null
        udpChannels.values.forEach { try { it.close() } catch (_: Exception) {} }
        tcpSockets.values.forEach { try { it.socket.close() } catch (_: Exception) {} }
        udpChannels.clear(); tcpSockets.clear()
        try { vpnOutput?.close() } catch (_: Exception) {}
        vpnOutput = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
        onStateChanged?.invoke(false)
        beep(400, 250)
    }

    // ═══════════════════════════════════════════════════════════════
    // VPN Main Loop (like Python's _loop thread)
    // ═══════════════════════════════════════════════════════════════
    private suspend fun vpnMainLoop(vpn: VpnService) = withContext(Dispatchers.IO) {
        val input = FileInputStream(vpnInterface!!.fileDescriptor)
        val buf = ByteArray(VPN_MTU)
        while (isActive && isRunning) {
            val len = try { input.read(buf) } catch (_: Exception) { break }
            if (len <= 0) continue

            val packetData = buf.copyOf(len)
            // Process inline on IO dispatcher — NO nested launch() per packet
            // This prevents coroutine flooding under game traffic load
            dispatch(packetData, vpn)
        }
        input.close()
    }

    /**
     * Dispatch ONE packet. Processed directly on the caller's thread (Dispatcher.IO).
     * UDP: forward outbound, filter happens on inbound reader
     * TCP: full state machine — SYN→connect, data→forward, FIN→teardown
     * IPv6: also handled
     */
    private fun dispatch(packet: ByteArray, vpn: VpnService) {
        if (packet.size < 20) return

        val version = (packet[0].toInt() and 0xFF) shr 4

        when (version) {
            4 -> dispatchIpv4(packet, vpn)
            6 -> dispatchIpv6(packet, vpn)
            // Other versions: silently ignore (non-IP traffic on TUN is rare)
        }
    }

    // ─── IPv4 dispatch ──────────────────────────────────────────────
    private fun dispatchIpv4(pkt: ByteArray, vpn: VpnService) {
        if (pkt.size < 20) return
        val protocol = pkt[9].toInt() and 0xFF
        val ihl = (pkt[0].toInt() and 0x0F) * 4
        if (pkt.size < ihl + 8) return

        when (protocol) {
            6  -> vpnHandleTcp4(pkt, vpn, ihl)
            17 -> vpnHandleUdp(pkt, vpn, ihl)
            // ICMP (1) and others — forward silently? No, we can't forward raw IP.
            // TUN only sees TCP/UDP from apps. Other protocols on mobile are negligible.
        }
    }

    // ─── IPv6 dispatch ──────────────────────────────────────────────
    private fun dispatchIpv6(pkt: ByteArray, vpn: VpnService) {
        // IPv6 header is 40 bytes fixed, then optional extension headers
        if (pkt.size < 40) return
        var nextHeader = pkt[6].toInt() and 0xFF
        var offset = 40

        // Skip known extension headers to find TCP/UDP
        for (i in 0..4) {  // Max 5 extension headers
            when (nextHeader) {
                6 -> { vpnHandleTcp6(pkt, vpn, offset); return }
                17 -> { vpnHandleUdp6(pkt, vpn, offset); return }
                0, 43, 44, 50, 51, 60, 135 -> {  // Hop-by-hop, Routing, Fragment, ESP, AH, Dest, Mobility
                    if (pkt.size < offset + 8) return
                    nextHeader = pkt[offset].toInt() and 0xFF
                    val extLen = (pkt[offset + 1].toInt() and 0xFF) * 8 + 8
                    offset += extLen
                }
                else -> return  // Unknown protocol
            }
        }
    }

    // ─── UDP (shared between IPv4 and IPv6) ─────────────────────────
    private fun vpnHandleUdp(pkt: ByteArray, vpn: VpnService, ipHdr: Int) {
        try {
            if (pkt.size < ipHdr + 8) return
            val sa = ByteArray(4).apply { System.arraycopy(pkt, 12, this, 0, 4) }
            val da = ByteArray(4).apply { System.arraycopy(pkt, 16, this, 0, 4) }
            val sp = ((pkt[ipHdr].toInt() and 0xFF) shl 8) or (pkt[ipHdr+1].toInt() and 0xFF)
            val dp = ((pkt[ipHdr+2].toInt() and 0xFF) shl 8) or (pkt[ipHdr+3].toInt() and 0xFF)
            val ulen = ((pkt[ipHdr+4].toInt() and 0xFF) shl 8) or (pkt[ipHdr+5].toInt() and 0xFF)
            val plen = ulen - 8
            if (plen <= 0) return

            val payload = ByteArray(plen)
            System.arraycopy(pkt, ipHdr + 8, payload, 0, plen)

            val key = "${InetAddress.getByAddress(sa).hostAddress}:$sp"
            val ch = udpChannels.getOrPut(key) {
                DatagramChannel.open().apply {
                    configureBlocking(false)
                    vpn.protect(socket())
                    socket().bind(InetSocketAddress(0))
                }
            }
            ch.send(
                ByteBuffer.wrap(payload),
                InetSocketAddress(InetAddress.getByAddress(da).hostAddress, dp)
            )
        } catch (_: Exception) {}
    }

    private fun vpnHandleUdp6(pkt: ByteArray, vpn: VpnService, ipHdr: Int) {
        try {
            if (pkt.size < ipHdr + 8) return
            val sa = ByteArray(16).apply { System.arraycopy(pkt, 8, this, 0, 16) }
            val da = ByteArray(16).apply { System.arraycopy(pkt, 24, this, 0, 16) }
            val sp = ((pkt[ipHdr].toInt() and 0xFF) shl 8) or (pkt[ipHdr+1].toInt() and 0xFF)
            val dp = ((pkt[ipHdr+2].toInt() and 0xFF) shl 8) or (pkt[ipHdr+3].toInt() and 0xFF)
            val ulen = ((pkt[ipHdr+4].toInt() and 0xFF) shl 8) or (pkt[ipHdr+5].toInt() and 0xFF)
            val plen = ulen - 8
            if (plen <= 0) return

            val payload = ByteArray(plen)
            System.arraycopy(pkt, ipHdr + 8, payload, 0, plen)

            val srcHost = InetAddress.getByAddress(sa).hostAddress
            val key = "[$srcHost]:$sp"
            val ch = udpChannels.getOrPut(key) {
                DatagramChannel.open().apply {
                    configureBlocking(false)
                    vpn.protect(socket())
                    socket().bind(InetSocketAddress(0))
                }
            }
            ch.send(
                ByteBuffer.wrap(payload),
                InetSocketAddress(InetAddress.getByAddress(da).hostAddress, dp)
            )
        } catch (_: Exception) {}
    }

    // ─── UDP Inbound Reader ─────────────────────────────────────────
    private suspend fun vpnUdpReader() = withContext(Dispatchers.IO) {
        val out = vpnOutput ?: return@withContext
        val buf = ByteBuffer.allocate(VPN_MTU)
        while (isActive && isRunning) {
            var any = false
            udpChannels.forEach { (key, ch) ->
                try {
                    buf.clear()
                    val src = ch.receive(buf) as? InetSocketAddress ?: return@forEach
                    val len = buf.position()
                    if (len <= 0) return@forEach
                    any = true

                    // ── FILTER LOGIC (matches Python + iptables) ──
                    // Python:  if 50 <= len(packet.raw) <= 250: DROP
                    // iptables: -m length --length 50:250 -j DROP
                    // totalLen = IP hdr (20) + UDP hdr (8) + payload = 28 + len
                    val totalLen = 28 + len
                    val isDns = src.port == 53

                    // DROP packets in range (match Python: inbound UDP 50-250)
                    if (!isDns && totalLen in minByte..maxByte) return@forEach

                    // Forward packet to device (server → device)
                    val payload = ByteArray(len); buf.flip(); buf.get(payload)

                    val isIpv6 = key.startsWith("[")
                    if (isIpv6) {
                        // Parse IPv6 key: "[addr]:port"
                        val closeBracket = key.indexOf(']')
                        val ip = key.substring(1, closeBracket)
                        val port = key.substring(closeBracket + 2).toIntOrNull() ?: return@forEach
                        val ipHdr = 40
                        val totalLen6 = ipHdr + 8 + len
                        val b = ByteBuffer.allocate(totalLen6)
                        // IPv6 header
                        b.put((0x60).toByte()); b.put(0); b.put(0); b.put(0) // ver, traffic class, flow label
                        b.putShort((8 + len).toShort())  // payload length
                        b.put(17.toByte()); b.put(64.toByte())  // next header=UDP, hop limit
                        b.put(src.address.address)  // source = server
                        b.put(InetAddress.getByName(ip).address)  // dest = device
                        b.putShort(src.port.toShort()); b.putShort(port.toShort())
                        b.putShort((8 + len).toShort()); b.putShort(0)
                        b.put(payload)
                        synchronized(out) {
                            out.write(b.array())
                            out.flush()
                        }
                    } else {
                        val parts = key.split(":"); val ip = parts[0]
                        val port = parts[1].toIntOrNull() ?: return@forEach
                        val totalLen4 = 20 + 8 + len
                        val b = ByteBuffer.allocate(totalLen4)
                        // IPv4 header
                        b.put((0x45).toByte()); b.put(0); b.putShort(totalLen4.toShort()); b.putShort(0)
                        b.putShort(0x4000.toShort()); b.put(64.toByte()); b.put(17.toByte()); b.putShort(0)
                        b.put(src.address.address); b.put(InetAddress.getByName(ip).address)
                        b.putShort(src.port.toShort()); b.putShort(port.toShort())
                        b.putShort((8 + len).toShort()); b.putShort(0)
                        b.put(payload)
                        synchronized(out) {
                            out.write(b.array())
                            out.flush()
                        }
                    }
                } catch (_: Exception) {
                    try { ch.close() } catch (_: Exception) {}
                    udpChannels.remove(key)
                }
            }
            if (!any) delay(10)
        }
    }

    // ─── Periodic UDP channel cleanup ───────────────────────────────
    private suspend fun udpCleaner() = withContext(Dispatchers.IO) {
        while (isActive && isRunning) {
            delay(30_000)  // Every 30 seconds
            val toRemove = mutableListOf<String>()
            udpChannels.forEach { (key, ch) ->
                if (!ch.isOpen) {
                    try { ch.close() } catch (_: Exception) {}
                    toRemove.add(key)
                }
            }
            toRemove.forEach { udpChannels.remove(it) }
            if (toRemove.isNotEmpty()) {
                android.util.Log.d("FakeLag", "Cleaned ${toRemove.size} stale UDP channels, ${udpChannels.size} remaining")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TCP — Rewritten with proper state machine
    // ═══════════════════════════════════════════════════════════════

    private fun vpnHandleTcp4(pkt: ByteArray, vpn: VpnService, ipHdr: Int) {
        if (pkt.size < ipHdr + 20) return
        val sa = ByteArray(4).apply { System.arraycopy(pkt, 12, this, 0, 4) }
        val da = ByteArray(4).apply { System.arraycopy(pkt, 16, this, 0, 4) }
        val sp = ((pkt[ipHdr].toInt() and 0xFF) shl 8) or (pkt[ipHdr+1].toInt() and 0xFF)
        val dp = ((pkt[ipHdr+2].toInt() and 0xFF) shl 8) or (pkt[ipHdr+3].toInt() and 0xFF)
        val seq = read32(pkt, ipHdr + 4)
        val ackNum = read32(pkt, ipHdr + 8)
        val flags = pkt[ipHdr+13].toInt() and 0xFF
        val thl = ((pkt[ipHdr+12].toInt() and 0xFF) shr 4) * 4
        val plen = pkt.size - ipHdr - thl

        vpnHandleTcpCommon(
            pkt = pkt, vpn = vpn,
            sa = sa, da = da, sp = sp, dp = dp,
            seq = seq, ackNum = ackNum, flags = flags,
            ipHdr = ipHdr, thl = thl, plen = plen,
            isIpv6 = false
        )
    }

    private fun vpnHandleTcp6(pkt: ByteArray, vpn: VpnService, ipHdr: Int) {
        if (pkt.size < ipHdr + 20) return
        val sa = ByteArray(16).apply { System.arraycopy(pkt, 8, this, 0, 16) }
        val da = ByteArray(16).apply { System.arraycopy(pkt, 24, this, 0, 16) }
        val sp = ((pkt[ipHdr].toInt() and 0xFF) shl 8) or (pkt[ipHdr+1].toInt() and 0xFF)
        val dp = ((pkt[ipHdr+2].toInt() and 0xFF) shl 8) or (pkt[ipHdr+3].toInt() and 0xFF)
        val seq = read32(pkt, ipHdr + 4)
        val ackNum = read32(pkt, ipHdr + 8)
        val flags = pkt[ipHdr+13].toInt() and 0xFF
        val thl = ((pkt[ipHdr+12].toInt() and 0xFF) shr 4) * 4
        val plen = pkt.size - ipHdr - thl

        vpnHandleTcpCommon(
            pkt = pkt, vpn = vpn,
            sa = sa, da = da, sp = sp, dp = dp,
            seq = seq, ackNum = ackNum, flags = flags,
            ipHdr = ipHdr, thl = thl, plen = plen,
            isIpv6 = true
        )
    }

    /**
     * Unified TCP state machine — handles IPv4 and IPv6.
     *
     * States:
     *   SYN (no ACK)     → new connection, create socket, send SYN-ACK
     *   SYN+ACK (unusual) → device is responding to our SYN-ACK (3-way handshake step 3)
     *   ACK (no data)     → pure acknowledgment, update tracking only
     *   PSH+ACK (data)    → forward data to real socket, send ACK back
     *   FIN               → device wants to close, send FIN-ACK, teardown
     *   RST               → immediate teardown
     *
     * No nested coroutine launches — all work done inline on caller's thread.
     * Per-connection socket reader coroutine is spawned ONCE at SYN time.
     */
    private fun vpnHandleTcpCommon(
        pkt: ByteArray, vpn: VpnService,
        sa: ByteArray, da: ByteArray,
        sp: Int, dp: Int,
        seq: Long, @Suppress("UNUSED_PARAMETER") ackNum: Long,
        flags: Int,
        ipHdr: Int, thl: Int, plen: Int,
        isIpv6: Boolean
    ) {
        try {
            val syn = flags and 0x02 != 0
            val ack = flags and 0x10 != 0
            val rst = flags and 0x04 != 0
            val fin = flags and 0x01 != 0

            val srcHost = if (isIpv6) "[${InetAddress.getByAddress(sa).hostAddress}]"
                          else InetAddress.getByAddress(sa).hostAddress
            val key = "$srcHost:$sp"

            val out = vpnOutput ?: return

            when {
                // ── RST: immediate teardown ──
                rst -> {
                    tcpSockets.remove(key)?.socket?.close()
                }

                // ── SYN (no ACK): new connection from device ──
                syn && !ack -> {
                    // Close old connection if exists (reconnect)
                    tcpSockets.remove(key)?.socket?.close()

                    try {
                        val sock = Socket().apply { vpn.protect(this) }
                        // 5s connect timeout (was 3s) — some game servers are slow
                        sock.connect(
                            InetSocketAddress(InetAddress.getByAddress(da).hostAddress, dp),
                            5000
                        )
                        sock.soTimeout = 0  // No read timeout for established connections
                        sock.tcpNoDelay = true  // Disable Nagle for game latency

                        val c = TcpConn(sa, sp, da, dp, sock).apply {
                            theirSeq = seq + 1  // Next expected seq from device
                            // ourSeq already set to random by randSeq()
                            sent = 1  // SYN flag consumes 1 sequence number
                        }
                        tcpSockets[key] = c

                        // Send SYN-ACK to device
                        val synAckPkt = if (isIpv6)
                            tcpWrite6(da, dp, sa, sp, 0x12, c.ourSeq, c.theirSeq, ByteArray(0), synAck = true)
                        else
                            tcpWrite4(da, dp, sa, sp, 0x12, c.ourSeq, c.theirSeq, ByteArray(0), synAck = true)

                        if (synAckPkt != null) {
                            synchronized(out) {
                                out.write(synAckPkt)
                                out.flush()
                            }
                        }

                        // Spawn ONE socket→TUN reader per connection
                        scope?.launch(Dispatchers.IO) {
                            tcpSocketReader(key, c, out, isIpv6)
                        }
                    } catch (e: Exception) {
                        // Connection failed → RST
                        val rstPkt = if (isIpv6)
                            tcpWrite6(da, dp, sa, sp, 0x04, 0, seq + 1, ByteArray(0))
                        else
                            tcpWrite4(da, dp, sa, sp, 0x04, 0, seq + 1, ByteArray(0))
                        if (rstPkt != null) {
                            synchronized(out) {
                                out.write(rstPkt)
                                out.flush()
                            }
                        }
                    }
                }

                // ── FIN: device wants to close ──
                fin -> {
                    tcpSockets.remove(key)?.let { c ->
                        // FIN consumes 1 sequence number
                        c.theirSeq = seq + plen + 1
                        try { c.socket.close() } catch (_: Exception) {}

                        val finAckPkt = if (isIpv6)
                            tcpWrite6(da, dp, sa, sp, 0x11, c.ourSeq + c.sent, c.theirSeq, ByteArray(0))
                        else
                            tcpWrite4(da, dp, sa, sp, 0x11, c.ourSeq + c.sent, c.theirSeq, ByteArray(0))
                        if (finAckPkt != null) {
                            synchronized(out) {
                                out.write(finAckPkt)
                                out.flush()
                            }
                        }
                    }
                }

                // ── Data (PSH+ACK or just ACK with payload) ──
                plen > 0 -> {
                    tcpSockets[key]?.let { c ->
                        // Update device's sequence tracking
                        c.theirSeq = seq + plen

                        // Forward payload to real server
                        try {
                            c.socket.getOutputStream().write(pkt, ipHdr + thl, plen)
                            c.socket.getOutputStream().flush()
                        } catch (e: Exception) {
                            // Socket broken — send RST and clean up
                            tcpSockets.remove(key)
                            try { c.socket.close() } catch (_: Exception) {}
                            val rstPkt = if (isIpv6)
                                tcpWrite6(da, dp, sa, sp, 0x04, c.ourSeq + c.sent, c.theirSeq, ByteArray(0))
                            else
                                tcpWrite4(da, dp, sa, sp, 0x04, c.ourSeq + c.sent, c.theirSeq, ByteArray(0))
                            if (rstPkt != null) {
                                synchronized(out) {
                                    out.write(rstPkt)
                                    out.flush()
                                }
                            }
                            return
                        }

                        // ACK the device's data
                        val ackPkt = if (isIpv6)
                            tcpWrite6(da, dp, sa, sp, 0x10, c.ourSeq + c.sent, c.theirSeq, ByteArray(0))
                        else
                            tcpWrite4(da, dp, sa, sp, 0x10, c.ourSeq + c.sent, c.theirSeq, ByteArray(0))
                        if (ackPkt != null) {
                            synchronized(out) {
                                out.write(ackPkt)
                                out.flush()
                            }
                        }
                    }
                }

                // ── Pure ACK (no SYN/FIN/RST, no data): just track ──
                ack -> {
                    tcpSockets[key]?.let { _ ->
                        // Device acknowledges our data — nothing to do except track
                        // (not strictly needed since we don't do retransmission)
                    }
                }
                // Other cases (e.g., bare packet with no flags): silently ignore
            }
        } catch (_: Exception) {}
    }

    // ─── TCP Socket Reader (per connection) ──────────────────────────
    /**
     * Reads data from the real server socket and writes it to the TUN interface.
     * Runs as a dedicated coroutine per TCP connection.
     * On error/EOF → cleans up the connection.
     */
    private suspend fun tcpSocketReader(
        key: String, c: TcpConn,
        out: FileOutputStream,
        isIpv6: Boolean
    ) = withContext(Dispatchers.IO) {
        val buf = ByteArray(VPN_MTU)
        try {
            val input = c.socket.getInputStream()
            while (isActive && isRunning && c.socket.isConnected && !c.socket.isClosed) {
                // Small read — TUN MTU is only 1500, so read up to ~1400 bytes
                // to leave room for IP+TCP headers
                val maxPayload = if (isIpv6) 1400 else 1440
                val n = try {
                    input.read(buf, 0, minOf(maxPayload, buf.size))
                } catch (_: Exception) { -1 }

                if (n <= 0) break  // EOF or error

                val data = ByteArray(n)
                System.arraycopy(buf, 0, data, 0, n)

                val s = c.ourSeq + c.sent
                c.sent += n.toLong()

                val pkt = if (isIpv6)
                    tcpWrite6(c.dstAddr, c.dstPort, c.srcAddr, c.srcPort,
                        0x18, s, c.theirSeq, data)
                else
                    tcpWrite4(c.dstAddr, c.dstPort, c.srcAddr, c.srcPort,
                        0x18, s, c.theirSeq, data)

                if (pkt != null) {
                    synchronized(out) {
                        out.write(pkt)
                        out.flush()
                    }
                }
            }
        } catch (_: Exception) {}
        finally {
            // Clean up on EOF / error / stop
            tcpSockets.remove(key)
            try { c.socket.close() } catch (_: Exception) {}
            // Send FIN to device if still running
            if (isRunning && vpnOutput != null) {
                val finPkt = if (isIpv6)
                    tcpWrite6(c.dstAddr, c.dstPort, c.srcAddr, c.srcPort,
                        0x11, c.ourSeq + c.sent, c.theirSeq, ByteArray(0))
                else
                    tcpWrite4(c.dstAddr, c.dstPort, c.srcAddr, c.srcPort,
                        0x11, c.ourSeq + c.sent, c.theirSeq, ByteArray(0))
                if (finPkt != null) {
                    synchronized(out) {
                        try { out.write(finPkt); out.flush() } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // TCP packet builders (IPv4 and IPv6)
    // ═══════════════════════════════════════════════════════════════
    private fun tcpWrite4(
        sip: ByteArray, sp: Int, dip: ByteArray, dp: Int,
        flags: Int, seq: Long, ack: Long, data: ByteArray,
        synAck: Boolean = false
    ): ByteArray? = try {
        val thl = if (synAck) 24 else 20
        val total = 20 + thl + data.size
        val b = ByteBuffer.allocate(total)
        b.put((0x45).toByte()); b.put(0)
        b.putShort(total.toShort()); b.putShort(0)
        b.putShort(0x4000.toShort())
        b.put(64.toByte()); b.put(6.toByte()); b.putShort(0)
        b.put(sip); b.put(dip)
        b.putShort(sp.toShort()); b.putShort(dp.toShort())
        b.putInt(seq.toInt()); b.putInt(ack.toInt())
        b.put(((thl / 4) shl 4).toByte()); b.put(flags.toByte())
        b.putShort(65535.toShort())
        b.putShort(0); b.putShort(0)
        if (synAck) {
            b.put(2.toByte()); b.put(4.toByte()); b.putShort(1460.toShort())
        }
        if (data.isNotEmpty()) b.put(data)
        b.array()
    } catch (_: Exception) { null }

    private fun tcpWrite6(
        sip: ByteArray, sp: Int, dip: ByteArray, dp: Int,
        flags: Int, seq: Long, ack: Long, data: ByteArray,
        synAck: Boolean = false
    ): ByteArray? = try {
        val thl = if (synAck) 24 else 20
        val total = 40 + thl + data.size
        val b = ByteBuffer.allocate(total)
        // IPv6 header
        b.put((0x60).toByte()); b.put(0); b.put(0); b.put(0)
        b.putShort((thl + data.size).toShort())
        b.put(6.toByte()); b.put(64.toByte())
        b.put(sip); b.put(dip)
        // TCP header
        b.putShort(sp.toShort()); b.putShort(dp.toShort())
        b.putInt(seq.toInt()); b.putInt(ack.toInt())
        b.put(((thl / 4) shl 4).toByte()); b.put(flags.toByte())
        b.putShort(65535.toShort())
        b.putShort(0); b.putShort(0)
        if (synAck) {
            b.put(2.toByte()); b.put(4.toByte()); b.putShort(1460.toShort())
        }
        if (data.isNotEmpty()) b.put(data)
        b.array()
    } catch (_: Exception) { null }

    // ─── Helpers ─────────────────────────────────────────────────────
    private fun read32(pkt: ByteArray, off: Int): Long =
        ((pkt[off].toLong() and 0xFF) shl 24) or
        ((pkt[off+1].toLong() and 0xFF) shl 16) or
        ((pkt[off+2].toLong() and 0xFF) shl 8) or
        (pkt[off+3].toLong() and 0xFF)

    private fun beep(freq: Int, dur: Int) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val tg = ToneGenerator(AudioAttributes.USAGE_NOTIFICATION, 80)
                val t = if (freq > 500) ToneGenerator.TONE_PROP_ACK
                        else ToneGenerator.TONE_PROP_BEEP
                tg.startTone(t, dur)
                delay((dur + 100).toLong())
                tg.release()
            } catch (_: Exception) {}
        }
    }

    fun destroy() { stop() }
}
