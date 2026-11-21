package com.vknxworks.soundctrl.device

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The protocol log (Settings > Protocol log): every Bluetooth message SoundCtrl sends to or gets from a device,
 * as hex, while the switch is on. It lives in memory only (never on disk) and is gone when SoundCtrl closes, is
 * cleared, or the switch is turned off. Nothing is sent anywhere: you share it by hand from the log screen.
 *
 * Firmware transfers send thousands of large frames, so each entry keeps at most [MAX_BYTES] bytes (with the
 * full length noted) and the log keeps the newest [MAX_ENTRIES] entries.
 */
object ProtocolLog {
    const val MAX_ENTRIES = 1500
    const val MAX_BYTES = 48

    enum class Direction { OUT, IN }

    class Entry(val time: Long, val direction: Direction, val bytes: ByteArray, val length: Int)

    @Volatile
    var enabled: Boolean = false
        set(value) {
            field = value
            if (!value) clear()
        }

    private val lock = Any()
    private val entries = ArrayDeque<Entry>()

    private val _count = MutableStateFlow(0)
    /** How many entries there are; changes on every new one, so screens can follow along. */
    val count: StateFlow<Int> = _count

    private val _version = MutableStateFlow(0L)
    /** Goes up on every change, so a screen showing the entries knows to read them again. */
    val version: StateFlow<Long> = _version

    fun sent(bytes: ByteArray) = record(Direction.OUT, bytes)

    fun received(bytes: ByteArray) = record(Direction.IN, bytes)

    private fun record(direction: Direction, bytes: ByteArray) {
        if (!enabled) return
        val entry = Entry(System.currentTimeMillis(), direction, bytes.copyOf(minOf(bytes.size, MAX_BYTES)), bytes.size)
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
            _count.value = entries.size
            _version.value = _version.value + 1
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            _count.value = 0
            _version.value = _version.value + 1
        }
    }

    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /** One line: "12:03:04.512 → AA 41 (2 bytes)", with "…" when the frame was longer than what's kept. */
    fun line(e: Entry, zone: ZoneId = ZoneId.systemDefault()): String {
        val arrow = if (e.direction == Direction.OUT) "→" else "←"
        val hex = e.bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        val more = if (e.length > e.bytes.size) " …" else ""
        return "${timeFormat.format(Instant.ofEpochMilli(e.time).atZone(zone))} $arrow $hex$more (${e.length} B)"
    }

    /** The whole log as text, with [header] lines first (app version, phone, model). */
    fun text(header: List<String>): String = buildString {
        header.forEach { append(it).append('\n') }
        append('\n')
        snapshot().forEach { append(line(it)).append('\n') }
    }
}
