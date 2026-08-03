package ch.jeanrichard.nfcspoolwriter.data.nfc

import android.content.pm.PackageManager

/**
 * Static NFC capabilities of the *device* — see [DeviceCompatibility] for why both flags matter.
 *
 * An interface purely so the incompatible branches are unit testable: they can't be reproduced on
 * the development phone, which is compatible, and they're exactly the paths a user with the wrong
 * hardware would hit.
 */
interface NfcCapabilities {
    val hasNfcHardware: Boolean
    val supportsMifareClassic: Boolean
}

class AndroidNfcCapabilities(private val packageManager: PackageManager) : NfcCapabilities {

    override val hasNfcHardware: Boolean
        get() = packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)

    override val supportsMifareClassic: Boolean
        get() = packageManager.hasSystemFeature(MIFARE_CLASSIC_FEATURE)

    companion object {
        /**
         * MIFARE Classic is a proprietary NXP protocol rather than an NFC Forum standard, so
         * `android.nfc.tech.MifareClassic` only functions on phones with an NXP NFC controller.
         * Devices with Broadcom or Qualcomm chipsets enumerate a Classic tag but can never
         * authenticate a sector, and no amount of software can work around that.
         *
         * [PackageManager.FEATURE_NFC] does not distinguish them — every NFC phone declares it —
         * so this vendor feature is the only reliable signal (REQUIREMENTS.md §3).
         */
        const val MIFARE_CLASSIC_FEATURE = "com.nxp.mifare"
    }
}

/** Why the device can or cannot be used, in the order the checks are meaningful. */
enum class DeviceCompatibility {
    Compatible,

    /** No NFC radio at all. */
    NoNfcHardware,

    /** Has NFC, but a chipset that cannot speak MIFARE Classic. */
    NoMifareClassicSupport,
    ;

    companion object {
        /**
         * Hardware first, then chipset: a device with no radio at all shouldn't be told its NFC
         * controller is the wrong brand.
         */
        fun of(capabilities: NfcCapabilities): DeviceCompatibility = when {
            !capabilities.hasNfcHardware -> NoNfcHardware
            !capabilities.supportsMifareClassic -> NoMifareClassicSupport
            else -> Compatible
        }
    }
}
