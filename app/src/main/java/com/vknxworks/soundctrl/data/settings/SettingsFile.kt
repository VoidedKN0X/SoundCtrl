package com.vknxworks.soundctrl.data.settings

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Settings > Export settings / Import settings: SoundCtrl's own settings as a small JSON file the user keeps.
 * Only settings: no device list, no Bluetooth addresses, and nothing of the app lock (its PIN and keys belong to
 * this phone's secure hardware, and whether the lock is on is never in the file).
 *
 * An import never makes SoundCtrl less private than it is: the "Hide in screenshots" and "Hide in recent apps"
 * switches can be turned on by a file but never off, and the lock timeout can be made shorter but never longer.
 */
@Serializable
data class SettingsFileContent(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val appVersion: String? = null,
    val createdAt: String? = null,
    val autoConnect: Boolean? = null,
    val lockTimeout: String? = null,
    val hideScreenshots: Boolean? = null,
    val hideRecents: Boolean? = null,
) {
    companion object {
        const val FORMAT = "soundctrl-settings"
        const val VERSION = 1
    }
}

/** A file that isn't a SoundCtrl settings file ([newer]: one from a newer, incompatible version). */
class SettingsFileException(val newer: Boolean) : Exception()

object SettingsFile {
    /** A settings file is a few hundred bytes; anything far bigger isn't one. */
    const val MAX_BYTES = 64 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun export(s: AppSettings, appVersion: String, now: String): String = json.encodeToString(
        SettingsFileContent.serializer(),
        SettingsFileContent(
            appVersion = appVersion,
            createdAt = now,
            autoConnect = s.autoConnect,
            lockTimeout = s.lockTimeout.name,
            hideScreenshots = s.hideScreenshots,
            hideRecents = s.hideRecents,
        ),
    )

    fun parse(text: String): SettingsFileContent {
        val f = runCatching { json.decodeFromString(SettingsFileContent.serializer(), text) }.getOrNull()
            ?: throw SettingsFileException(newer = false)
        if (f.format != SettingsFileContent.FORMAT) throw SettingsFileException(newer = false)
        if (f.version > SettingsFileContent.VERSION) throw SettingsFileException(newer = true)
        if (f.version < 1) throw SettingsFileException(newer = false)
        return f
    }

    /** The settings after importing [f] over [current], with the privacy rules above applied. */
    fun applied(f: SettingsFileContent, current: AppSettings): AppSettings {
        val timeout = f.lockTimeout?.let { n -> LockTimeout.entries.firstOrNull { it.name == n } }
        return current.copy(
            autoConnect = f.autoConnect ?: current.autoConnect,
            lockTimeout = if (timeout != null && stricter(timeout, current.lockTimeout)) timeout else current.lockTimeout,
            hideScreenshots = current.hideScreenshots || f.hideScreenshots == true,
            hideRecents = current.hideRecents || f.hideRecents == true,
        )
    }

    /** Whether [a] asks again at least as soon as [b] ("only after a restart" is the least strict). */
    private fun stricter(a: LockTimeout, b: LockTimeout): Boolean {
        fun rank(t: LockTimeout) = if (t.millis < 0) Long.MAX_VALUE else t.millis
        return rank(a) <= rank(b)
    }
}
