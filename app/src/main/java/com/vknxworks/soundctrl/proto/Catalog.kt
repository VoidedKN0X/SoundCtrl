package com.vknxworks.soundctrl.proto

enum class Kind { SPEAKER, HEADPHONES, EARBUDS }

enum class Chip { VIMICRO, CSR, QCC, TWS, MTK, BES, AIROHA, REALTEK, AVNERA, OTHER }

/** How SoundCtrl talks to the device. */
enum class Control { SPEAKER_AA, SPEAKER_P4, HP_P4, HP_AA, HP_AIROHA, NONE }

/** How the device takes new firmware (the "update family"). */
enum class UpdateFamily {
    /** Speaker update protocol over AA (`AA 43/44/45`): implemented. */
    SPEAKER_AA,
    /** Older CSR/QCC/TWS speakers: start command not established. */
    SPEAKER_CONNECT_PLUS,
    /** Protocol-4 speakers: device-paced by offset, implemented. */
    SPEAKER_P4,
    /** Protocol-4 headphones (BES, Realtek): device-paced by offset, implemented. */
    HP_P4,
    /** Harman "HmOtaMgr" (`AA F0`): not established (TLV lengths and CRC variant). */
    HM_OTA_MGR,
    /** BES OTA SDK: ambiguous image size/CRC convention. */
    BES,
    /** Airoha FOTA: established by the headphone app but not implemented in 0.1. */
    AIROHA,
    /** Avnera (legacy Everest): framing obfuscated. */
    AVNERA,
    UNKNOWN,
    NONE;

    val writeSupported: Boolean get() = this == SPEAKER_AA || this == SPEAKER_P4 || this == HP_P4
}

data class Product(
    val pid: String,
    val name: String,
    val kind: Kind,
    val chip: Chip,
    val control: Control,
    val update: UpdateFamily,
    /** "spk:<path>", "spkdyn", "hpamz", "hpsto", "xml:<url>" or "". */
    val updateSource: String,
    val features: Set<String>,
) {
    val isSpeaker get() = kind == Kind.SPEAKER
    fun has(feature: String) = feature in features
}

object Catalog {
    val products: Map<String, Product> by lazy {
        PRODUCT_ROWS.mapNotNull { parse(it) }.associateBy { it.pid }
    }

    internal fun parse(row: String): Product? {
        val c = row.split('|')
        if (c.size < 8) return null
        return Product(
            pid = c[0].lowercase(),
            name = c[1],
            kind = enumValueOrNull<Kind>(c[2]) ?: return null,
            chip = enumValueOrNull<Chip>(c[3]) ?: Chip.OTHER,
            control = enumValueOrNull<Control>(c[4]) ?: Control.NONE,
            update = enumValueOrNull<UpdateFamily>(c[5]) ?: UpdateFamily.UNKNOWN,
            updateSource = c[6],
            features = c[7].split(',').filter { it.isNotBlank() }.toSet(),
        )
    }

    fun find(pid: String?): Product? = pid?.lowercase()?.let { products[it] }
}

private inline fun <reified E : Enum<E>> enumValueOrNull(name: String): E? = enumValues<E>().firstOrNull { it.name == name }

/** What a JBL advertisement says: the product id (as 4 lower-case hex digits) and, if sent, the model/colour id. */
data class Advert(val pid: String, val modelId: Int?)

object AdvertParser {
    const val HARMAN_COMPANY = 0x0057
    const val HARMAN_COMPANY_HEADPHONES = 0x0ECB
    const val SERVICE_FDDF = "0000fddf-0000-1000-8000-00805f9b34fb"
    const val SERVICE_DFFD = "0000dffd-0000-1000-8000-00805f9b34fb"
    const val SERVICE_FC69 = "0000fc69-0000-1000-8000-00805f9b34fb"

    /** Product id = first two bytes, little-endian (both apps' BroadcastParse / SpeakerBleBroadCastParser). */
    fun parse(data: ByteArray?): Advert? {
        if (data == null || data.size < 2) return null
        val pid = "%04x".format(data.u16le(0))
        return Advert(pid, if (data.size >= 3) data.u8(2) else null)
    }
}
