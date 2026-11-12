package com.vknxworks.soundctrl.update

import com.vknxworks.soundctrl.proto.UpdateServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/**
 * The only network code in SoundCtrl: plain anonymous HTTPS GETs to Harman's public file server.
 * No cookies, no account, no identifiers; redirects are followed only within the allowed hosts.
 */
object FileServer {
    private const val MAX_CONFIG = 256 * 1024
    private const val MAX_IMAGE = 64 * 1024 * 1024

    class Refused(msg: String) : IOException(msg)

    suspend fun getText(url: String): String = String(get(url, MAX_CONFIG) {}, Charsets.UTF_8)

    suspend fun getImage(url: String, progress: (Float) -> Unit): ByteArray = get(url, MAX_IMAGE, progress)

    private suspend fun get(start: String, max: Int, progress: (Float) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        var url = start
        repeat(4) {
            if (!UpdateServer.isAllowed(url)) throw Refused("not an allowed address")
            val c = URL(url).openConnection() as HttpsURLConnection
            try {
                c.instanceFollowRedirects = false
                c.useCaches = false
                c.connectTimeout = 15_000
                c.readTimeout = 30_000
                c.setRequestProperty("User-Agent", "SoundCtrl")
                c.setRequestProperty("Accept-Encoding", "identity")
                val code = c.responseCode
                if (code in 300..399) {
                    url = c.getHeaderField("Location")?.let { URL(URL(url), it).toString() } ?: throw IOException("redirect without location")
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
                val total = c.contentLengthLong
                if (total > max) throw Refused("file too large")
                val out = ByteArrayOutputStream(if (total > 0) total.toInt() else 64 * 1024)
                c.inputStream.use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > max) throw Refused("file too large")
                        if (total > 0) progress(out.size().toFloat() / total)
                    }
                }
                return@withContext out.toByteArray()
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }
}
