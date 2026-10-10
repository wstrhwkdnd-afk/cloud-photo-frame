package com.cloudframe.tv

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * A tiny web page on the home network so you can sign in from a phone instead
 * of typing with the remote. Runs only while the setup screen is open and the
 * TV is signed out, and only accepts submissions with the code shown on the TV.
 */
class SetupServer(
    private val context: Context,
    private val onSubmit: suspend (Map<String, String>) -> String?,
) {
    var pin: String = newPin(); private set
    private var failedAttempts = 0
    private var socket: ServerSocket? = null

    val address: String?
        get() = localIp(context)?.let { "http://$it:$PORT" }

    fun start() {
        if (socket != null) return
        val server = try { ServerSocket(PORT) } catch (e: Exception) { return }
        socket = server
        thread(name = "setup-server", isDaemon = true) {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: SocketException) { break }
                thread(isDaemon = true) {
                    // A browser may open a connection and never send anything; never let that crash the app
                    try { client.use { handle(it) } } catch (e: Exception) { }
                }
            }
        }
    }

    fun stop() {
        socket?.close()
        socket = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return
        var length = 0
        while (true) {
            val h = reader.readLine() ?: break
            if (h.isEmpty()) break
            if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val (method, path) = requestLine.split(" ").let { it.getOrElse(0) { "" } to it.getOrElse(1) { "/" } }
        val html = if (method == "POST" && path == "/" && length in 1..8192) {
            val buf = CharArray(length)
            var read = 0
            while (read < length) { val n = reader.read(buf, read, length - read); if (n < 0) break; read += n }
            val form = parseForm(String(buf, 0, read))
            page(submit(form))
        } else page(null)
        val body = html.toByteArray(Charsets.UTF_8)
        client.getOutputStream().apply {
            write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
            write(body)
            flush()
        }
    }

    @Synchronized
    private fun submit(form: Map<String, String>): String {
        if (form["pin"]?.trim() != pin) {
            if (++failedAttempts >= 5) { pin = newPin(); failedAttempts = 0 }
            return "<p class=err>That code doesn't match the one on your TV.</p>"
        }
        val error = runBlocking { onSubmit(form) }
        return if (error == null) "<p class=ok>✓ Signed in. You can close this page and continue on the TV.</p>"
        else "<p class=err>${escape(error)}</p>"
    }

    private fun page(result: String?) = """
        <!doctype html><html><head><meta charset=utf-8>
        <meta name=viewport content="width=device-width,initial-scale=1">
        <title>Cloud Photo Frame setup</title><style>
        body{font:16px -apple-system,Roboto,sans-serif;max-width:420px;margin:24px auto;padding:0 16px;color:#222}
        input{width:100%;padding:10px;margin:4px 0 12px;font:inherit;box-sizing:border-box}
        button{padding:10px 18px;font:inherit} .err{color:#c00} .ok{color:#080} small{color:#666}
        details{margin-top:16px}</style></head><body>
        <h2>Cloud Photo Frame</h2>${result ?: ""}
        <form method=post>
        <label>Code shown on your TV</label><input name=pin inputmode=numeric autocomplete=off required>
        <label>Degoo email</label><input name=email type=email autocomplete=username>
        <label>Degoo password</label><input name=password type=password autocomplete=current-password>
        <details><summary>Or paste a session token instead</summary>
        <label>Access token</label><input name=access_token>
        <label>Refresh token (optional)</label><input name=refresh_token></details>
        <p><button>Sign in on TV</button></p></form>
        <small>Your password is saved on the TV, encrypted, so it can sign in again by itself if Degoo ends the session. Signing out on the TV deletes it.
        This page only works on your home network while the TV setup screen is open.</small>
        </body></html>""".trimIndent()

    companion object {
        const val PORT = 8642

        private fun newPin() = Random.nextInt(100000, 999999).toString()

        private fun parseForm(body: String): Map<String, String> = body.split("&").mapNotNull {
            val k = it.substringBefore('=', "")
            if (k.isEmpty()) null
            else URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
        }.toMap()

        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /** The TV's address on the home network (Wi-Fi or Ethernet). */
        private fun localIp(context: Context): String? = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.getLinkProperties(cm.activeNetwork)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}
