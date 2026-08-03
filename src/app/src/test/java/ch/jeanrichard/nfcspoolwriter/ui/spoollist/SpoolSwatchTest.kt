package ch.jeanrichard.nfcspoolwriter.ui.spoollist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpoolSwatchTest {

    @Test
    fun `six digit colour is parsed`() {
        assertEquals(0x1A6BD8, parseSwatchColor("1A6BD8"))
    }

    @Test
    fun `leading hash is ignored`() {
        assertEquals(0x00FF00, parseSwatchColor("#00FF00"))
    }

    @Test
    fun `lowercase hex is accepted`() {
        assertEquals(0xABCDEF, parseSwatchColor("abcdef"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals(0xABCDEF, parseSwatchColor("  ABCDEF  "))
    }

    @Test
    fun `alpha channel is dropped`() {
        assertEquals(0x112233, parseSwatchColor("11223344"))
    }

    @Test
    fun `three digit shorthand is expanded`() {
        assertEquals(0xFF00AA, parseSwatchColor("F0A"))
    }

    @Test
    fun `null colour has no swatch colour`() {
        assertNull(parseSwatchColor(null))
    }

    @Test
    fun `blank colour has no swatch colour`() {
        assertNull(parseSwatchColor("   "))
    }

    @Test
    fun `non hex colour has no swatch colour`() {
        assertNull(parseSwatchColor("ZZZZZZ"))
    }

    @Test
    fun `unexpected length has no swatch colour`() {
        assertNull(parseSwatchColor("FF00"))
    }

    @Test
    fun `white takes dark text`() {
        assertTrue(prefersDarkText(0xFFFFFF))
    }

    @Test
    fun `black takes light text`() {
        assertFalse(prefersDarkText(0x000000))
    }

    @Test
    fun `saturated yellow takes dark text`() {
        assertTrue(prefersDarkText(0xFFFF00))
    }

    @Test
    fun `mid blue takes light text`() {
        assertFalse(prefersDarkText(0x1A6BD8))
    }

    @Test
    fun `green weighs more than red at the same channel value`() {
        assertTrue(prefersDarkText(0x00FF00))
        assertFalse(prefersDarkText(0xFF0000))
    }

    @Test
    fun `dark end of the sRGB transfer curve stays on light text`() {
        assertFalse(prefersDarkText(0x0A0A0A))
    }

    @Test
    fun `short material keeps the largest step`() {
        val label = swatchLabel("PLA")
        assertEquals("PLA", label.text)
        assertEquals(13f, label.fontSizeSp)
        assertFalse(label.truncated)
    }

    @Test
    fun `four character material keeps the largest step`() {
        assertEquals(13f, swatchLabel("PETG").fontSizeSp)
    }

    @Test
    fun `six character material steps down once`() {
        val label = swatchLabel("PA6-CF")
        assertEquals("PA6-CF", label.text)
        assertEquals(11f, label.fontSizeSp)
        assertFalse(label.truncated)
    }

    @Test
    fun `seven character material steps down twice`() {
        val label = swatchLabel("TPU 95A")
        assertEquals("TPU 95A", label.text)
        assertEquals(9f, label.fontSizeSp)
        assertFalse(label.truncated)
    }

    @Test
    fun `over long material is truncated`() {
        val label = swatchLabel("Polycarbonate")
        assertEquals("POLYCA…", label.text)
        assertEquals(9f, label.fontSizeSp)
        assertTrue(label.truncated)
    }

    @Test
    fun `material is uppercased`() {
        assertEquals("PLA", swatchLabel("pla").text)
    }

    @Test
    fun `inner whitespace is collapsed`() {
        assertEquals("PLA HT", swatchLabel(" pla   ht ").text)
    }

    @Test
    fun `missing material falls back to a question mark`() {
        val label = swatchLabel(null)
        assertEquals("?", label.text)
        assertEquals(13f, label.fontSizeSp)
        assertFalse(label.truncated)
    }

    @Test
    fun `blank material falls back to a question mark`() {
        assertEquals("?", swatchLabel("   ").text)
    }
}
