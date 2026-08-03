package ch.jeanrichard.nfcspoolwriter.data.nfc

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCompatibilityTest {

    private class FakeCapabilities(
        override val hasNfcHardware: Boolean,
        override val supportsMifareClassic: Boolean,
    ) : NfcCapabilities

    /** The development phone: NXP PN54x, `com.nxp.mifare` present (REQUIREMENTS.md §3). */
    @Test
    fun `nfc plus mifare support is compatible`() {
        val result = DeviceCompatibility.of(FakeCapabilities(true, true))

        assertEquals(DeviceCompatibility.Compatible, result)
    }

    @Test
    fun `no nfc hardware is reported as such`() {
        val result = DeviceCompatibility.of(FakeCapabilities(false, false))

        assertEquals(DeviceCompatibility.NoNfcHardware, result)
    }

    /** The case this whole check exists for: NFC present, but a non-NXP chipset. */
    @Test
    fun `nfc without mifare support is reported as unsupported chipset`() {
        val result = DeviceCompatibility.of(FakeCapabilities(true, false))

        assertEquals(DeviceCompatibility.NoMifareClassicSupport, result)
    }

    /**
     * Nonsensical in practice, but pins the check order: a device with no radio must not be told its
     * NFC controller is the wrong brand.
     */
    @Test
    fun `missing hardware takes precedence over chipset support`() {
        val result = DeviceCompatibility.of(FakeCapabilities(false, true))

        assertEquals(DeviceCompatibility.NoNfcHardware, result)
    }

    @Test
    fun `feature name is the NXP vendor feature`() {
        assertEquals("com.nxp.mifare", AndroidNfcCapabilities.MIFARE_CLASSIC_FEATURE)
    }
}
