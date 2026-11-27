package com.zee.clone.data

import android.util.Base64
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Tests a proxy exactly the way the native hook will use it (v3.1.0):
 * open a TCP socket to the proxy, handshake (SOCKS5 or HTTP CONNECT),
 * then GET http://ip-api.com/json/ through the tunnel and read the exit
 * IP + country from the answer.
 *
 * Plain sockets only — no engine, no clone involved, so this runs before
 * any proxy is saved. MUST be called off the main thread (network I/O).
 * ip-api.com's free endpoint is plain HTTP on port 80; its JSON answers
 * {"status":"success","country":"Germany","query":"1.2.3.4",...}.
 */
sealed class ProxyTestResult {
    data class Success(val exitIp: String, val country: String) : ProxyTestResult()
    data class Failed(val reason: String) : ProxyTestResult()
}

object ProxyTester {

    fun test(settings: ProxySettings, timeoutMs: Int = 15_000): ProxyTestResult {
        if (settings.host.isBlank()) return ProxyTestResult.Failed("Proxy host is empty.")
        if (settings.port !in 1..65535) return ProxyTestResult.Failed("Proxy port must be 1–65535.")
        val socket = Socket()
        try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(settings.host, settings.port), timeoutMs)
            val out = socket.getOutputStream()
            val inp = socket.getInputStream()
            if (settings.type == "http") {
                if (!httpHandshake(out, inp, settings)) {
                    return ProxyTestResult.Failed("HTTP proxy refused the CONNECT request.")
                }
            } else {
                if (!socks5Handshake(out, inp, settings)) {
                    return ProxyTestResult.Failed("SOCKS5 handshake failed (wrong host/port, or wrong user/pass).")
                }
            }
            // Through the tunnel now: plain HTTP to ip-api.com.
            val req = "GET /json/ HTTP/1.0\r\nHost: ip-api.com\r\n\r\n"
            out.write(req.toByteArray(Charsets.US_ASCII))
            out.flush()
            val body = readHttpBody(inp) ?: return ProxyTestResult.Failed("No answer came back through the proxy.")
            val json = JSONObject(body)
            if (json.optString("status") != "success") {
                return ProxyTestResult.Failed("ip-api.com said: ${json.optString("message", "failed")}")
            }
            val ip = json.optString("query", "")
            val country = json.optString("country", "")
            if (ip.isBlank()) return ProxyTestResult.Failed("No exit IP in the answer.")
            return ProxyTestResult.Success(ip, country.ifBlank { "unknown country" })
        } catch (e: Exception) {
            return ProxyTestResult.Failed(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readExact(inp: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = inp.read(buf, got, n - got)
            if (r <= 0) return null
            got += r
        }
        return buf
    }

    private fun socks5Handshake(out: OutputStream, inp: InputStream, s: ProxySettings): Boolean {
        // Greeting: VER=5, 1 method (0x02 user/pass, else 0x00 no-auth).
        val greet = if (s.user.isNotEmpty()) byteArrayOf(0x05, 0x01, 0x02) else byteArrayOf(0x05, 0x01, 0x00)
        out.write(greet)
        out.flush()
        val mrep = readExact(inp, 2) ?: return false
        if (mrep[0] != 0x05.toByte()) return false
        if (mrep[1] == 0x02.toByte()) {
            val userB = s.user.toByteArray(Charsets.UTF_8)
            val passB = s.pass.toByteArray(Charsets.UTF_8)
            val auth = ByteArray(3 + userB.size + passB.size)
            auth[0] = 0x01
            auth[1] = userB.size.toByte()
            userB.copyInto(auth, 2)
            auth[2 + userB.size] = passB.size.toByte()
            passB.copyInto(auth, 3 + userB.size)
            out.write(auth)
            out.flush()
            val arep = readExact(inp, 2) ?: return false
            if (arep[0] != 0x01.toByte() || arep[1] != 0x00.toByte()) return false
        } else if (mrep[1] != 0x00.toByte()) {
            return false
        }
        // CONNECT ip-api.com:80, domain form (remote DNS).
        val hostB = "ip-api.com".toByteArray(Charsets.US_ASCII)
        val req = ByteArray(7 + hostB.size)
        req[0] = 0x05
        req[1] = 0x01
        req[2] = 0x00
        req[3] = 0x03
        req[4] = hostB.size.toByte()
        hostB.copyInto(req, 5)
        req[5 + hostB.size] = 0x00
        req[6 + hostB.size] = 80
        out.write(req)
        out.flush()
        val rep = readExact(inp, 4) ?: return false
        if (rep[0] != 0x05.toByte() || rep[1] != 0x00.toByte()) return false
        val atyp = rep[3].toInt()
        val addrLen = when (atyp) {
            0x01 -> 4
            0x04 -> 16
            0x03 -> (readExact(inp, 1)?.get(0)?.toInt()?.and(0xFF)) ?: return false
            else -> return false
        }
        if (readExact(inp, addrLen + 2) == null) return false
        return true
    }

    private fun httpHandshake(out: OutputStream, inp: InputStream, s: ProxySettings): Boolean {
        val req = StringBuilder()
        req.append("CONNECT ip-api.com:80 HTTP/1.1\r\n")
        req.append("Host: ip-api.com:80\r\n")
        if (s.user.isNotEmpty()) {
            val cred = Base64.encodeToString(
                "${s.user}:${s.pass}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP
            )
            req.append("Proxy-Authorization: Basic $cred\r\n")
        }
        req.append("\r\n")
        out.write(req.toString().toByteArray(Charsets.US_ASCII))
        out.flush()
        val head = StringBuilder()
        val one = ByteArray(1)
        while (head.length < 8192) {
            if (inp.read(one) <= 0) return false
            head.append(one[0].toInt().toChar())
            if (head.endsWith("\r\n\r\n")) break
        }
        val firstLine = head.toString().substringBefore("\r\n")
        return firstLine.contains(" 200")
    }

    /** Reads one HTTP response and returns its body (Content-Length aware). */
    private fun readHttpBody(inp: InputStream): String? {
        val head = StringBuilder()
        val one = ByteArray(1)
        while (head.length < 8192) {
            if (inp.read(one) <= 0) return null
            head.append(one[0].toInt().toChar())
            if (head.endsWith("\r\n\r\n")) break
        }
        val headText = head.toString()
        val length = Regex("(?i)content-length:\\s*(\\d+)").find(headText)
            ?.groupValues?.get(1)?.toIntOrNull()
        val bodyBytes = if (length != null) {
            readExact(inp, length) ?: return null
        } else {
            inp.readBytes()
        }
        return String(bodyBytes, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }
}
