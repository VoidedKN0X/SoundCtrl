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

    /**
     * Downloads a firmware image, carrying on from [from] when it is the same address. A broken connection throws
     * [DownloadInterrupted] with what arrived so far, so the next call asks the server for only the rest
     * (`Range: bytes=N-`, with `If-Range` so a changed file is sent whole instead of glued onto the old part).
     * A server that answers with the whole file again (200) simply starts over. Everything stays in memory; the
     * MD5 check on the finished image catches anything that went wrong.
     */
    suspend fun getImageResumable(start: String, from: PartialImage?, progress: (Float) -> Unit): PartialImage = withContext(Dispatchers.IO) {
        var url = start
        val resume = from?.takeIf { it.url == start && it.resumable && it.count > 0 }
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
                if (resume != null) {
                    c.setRequestProperty("Range", "bytes=${resume.count}-")
                    resume.validator?.let { c.setRequestProperty("If-Range", it) }
                }
                val code = try {
                    c.responseCode
                } catch (e: IOException) {
                    throw DownloadInterrupted(resume, e)
                }
                if (code in 300..399) {
                    url = c.getHeaderField("Location")?.let { URL(URL(url), it).toString() } ?: throw IOException("redirect without location")
                    return@repeat
                }
                val image: PartialImage = when {
                    code == 206 && resume != null -> {
                        val range = ContentRange.parse(c.getHeaderField("Content-Range"))
                        // Only the exact rest of the same file is accepted; anything else starts over.
                        if (range == null || range.first != resume.count.toLong() || range.total != resume.total) {
                            throw DownloadInterrupted(null, IOException("the server sent a different part"))
                        }
                        resume
                    }
                    code == HttpURLConnection.HTTP_OK -> {
                        val total = c.contentLengthLong
                        if (total > MAX_IMAGE) throw Refused("file too large")
                        PartialImage.start(url = start, total = total, validator = validatorOf(c))
                    }
                    else -> throw IOException("HTTP $code")
                }
                try {
                    c.inputStream.use { input ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            if (!image.append(buf, n, MAX_IMAGE)) throw Refused("file too large")
                            if (image.total > 0) progress(image.count.toFloat() / image.total)
                        }
                    }
                } catch (e: Refused) {
                    throw e
                } catch (e: IOException) {
                    throw DownloadInterrupted(image.takeIf { it.resumable && it.count > 0 }, e)
                }
                if (image.total > 0 && image.count.toLong() != image.total) {
                    throw DownloadInterrupted(image.takeIf { it.resumable && it.count > 0 }, IOException("connection closed early"))
                }
                return@withContext image
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    /** A strong ETag, or else Last-Modified: what `If-Range` needs to make sure the rest is of the same file. */
    private fun validatorOf(c: HttpURLConnection): String? =
        c.getHeaderField("ETag")?.takeIf { it.startsWith("\"") } ?: c.getHeaderField("Last-Modified")

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
                val out = ExactBuffer(if (total > 0) total.toInt() else 64 * 1024)
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
                return@withContext out.bytes()
            } finally {
                c.disconnect()
            }
        }
        throw IOException("too many redirects")
    }

    /**
     * Collects a download. When the server said how big the file is, the buffer is made exactly that size and is
     * handed over as it is once full, so a large firmware image isn't held in memory twice. The bytes are the same
     * either way.
     */
    private class ExactBuffer(size: Int) : ByteArrayOutputStream(size) {
        fun bytes(): ByteArray = if (count == buf.size) buf else toByteArray()
    }
}
