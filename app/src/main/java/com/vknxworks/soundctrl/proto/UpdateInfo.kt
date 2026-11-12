package com.vknxworks.soundctrl.proto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.security.MessageDigest

/**
 * Harman's public file server: where the JBL apps look for firmware, and how its answer reads.
 * The only network access in SoundCtrl. See PROTOCOL.md §4.
 */
object UpdateServer {
    /** The only hosts SoundCtrl will ever contact (HTTPS only). */
    val ALLOWED_HOSTS = setOf("storage.harman.com", "appstorage.onecloud.harman.com")

    private const val SPEAKER_LEGACY = "https://storage.harman.com/release/portable/"
    private const val SPEAKER_DYNAMIC = "https://appstorage.onecloud.harman.com/jbl_portable/ota/"
    private const val HP_AMAZON = "https://appstorage.onecloud.harman.com/jbl_headphones/ota/"
    private const val HP_STORAGE = "https://storage.harman.com/MyJBLHeadphones/ota/release/"

    fun isAllowed(url: String): Boolean = try {
        val u = URI(url)
        u.scheme == "https" && u.host?.lowercase() in ALLOWED_HOSTS && u.userInfo == null && (u.port == -1 || u.port == 443)
    } catch (_: Exception) {
        false
    }

    /** The check URL for a product, or null when the JBL apps don't have one either. */
    fun configUrl(p: Product): String? = when {
        p.updateSource.startsWith("spk:") -> SPEAKER_LEGACY + p.updateSource.removePrefix("spk:")
        p.updateSource == "spkdyn" -> "$SPEAKER_DYNAMIC${p.pid}/ota_config.json"
        p.updateSource == "hpamz" -> "$HP_AMAZON${p.pid}/ota_config.json"
        p.updateSource == "hpsto" -> "$HP_STORAGE${p.pid}/ota_config.json"
        p.updateSource.startsWith("xml:") -> p.updateSource.removePrefix("xml:")
        else -> null
    }?.takeIf { isAllowed(it) }

    /** Turns the server's answer into what's on offer. */
    fun parse(p: Product, configUrl: String, body: String): Offer? =
        if (configUrl.endsWith(".xml", ignoreCase = true)) parseXml(body) else parseJson(p, body)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * JSON answers: speakers' older form `{version, md5, binUrl}` (binUrl relative to the legacy host
     * or absolute); newer form (speakers and headphones) `{pid, firmware: {version, md5, bin}}` with
     * the image next to the config file.
     */
    fun parseJson(p: Product, body: String): Offer? {
        val o = try { json.parseToJsonElement(body.trim().trimStart('﻿')).jsonObject } catch (_: Exception) { return null }
        val fw = o["firmware"] as? JsonObject
        if (fw != null) {
            val pid = o.str("pid")
            if (pid != null && !pid.equals(p.pid, ignoreCase = true)) return null
            val version = fw.str("version") ?: return null
            val md5 = fw.str("md5") ?: return null
            val bin = fw.str("bin") ?: return null
            val base = when (p.updateSource) {
                "spkdyn" -> SPEAKER_DYNAMIC
                "hpsto" -> HP_STORAGE
                else -> HP_AMAZON
            }
            return Offer(version, md5, "$base${p.pid}/$bin", fw.str("only_support_greater_than"))
        }
        val version = o.str("version") ?: return null
        val md5 = o.str("md5") ?: return null
        val bin = o.str("binUrl") ?: return null
        val url = if (bin.startsWith("http")) bin else SPEAKER_LEGACY + bin.trimStart('/')
        return Offer(version, md5, url, null)
    }

    private fun JsonObject.str(k: String): String? = (this[k])?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.takeIf { it.isNotBlank() }

    private val releaseRe = Regex("""<Release\b([^>]*)>\s*([^<]+?)\s*</Release>""", RegexOption.IGNORE_CASE)
    private val attrRe = Regex("""(\w+)\s*=\s*"([^"]*)"""")

    /** Older headphones: `<Release name="" version="" md5="">url</Release>`, "boot" entries skipped. */
    fun parseXml(body: String): Offer? {
        for (m in releaseRe.findAll(body)) {
            val attrs = attrRe.findAll(m.groupValues[1]).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            if (attrs["name"].equals("boot", ignoreCase = true)) continue
            val version = attrs["version"] ?: continue
            val md5 = attrs["md5"] ?: continue
            return Offer(version, md5, m.groupValues[2].trim(), null)
        }
        return null
    }
}

/** A firmware image on offer. */
data class Offer(val version: String, val md5: String, val url: String, val onlyAbove: String?) {
    /** Newer than [current] (and the device is new enough for it)? null when versions can't be compared. */
    fun isNewerThan(current: String): Boolean? {
        val c = compareVersions(version, current) ?: return null
        if (onlyAbove != null) {
            val ok = compareVersions(current, onlyAbove) ?: return null
            if (ok <= 0) return false
        }
        return c > 0
    }
}

object Md5 {
    fun hex(data: ByteArray): String = MessageDigest.getInstance("MD5").digest(data).hex()

    fun isValidHex(s: String): Boolean = s.trim().length == 32 && s.trim().all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }

    fun matches(data: ByteArray, expected: String): Boolean = isValidHex(expected) && hex(data).equals(expected.trim(), ignoreCase = true)
}
