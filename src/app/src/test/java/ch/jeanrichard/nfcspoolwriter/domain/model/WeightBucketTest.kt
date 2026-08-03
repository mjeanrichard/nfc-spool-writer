package ch.jeanrichard.nfcspoolwriter.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WeightBucketTest {

    @Test
    fun `codes match the spec table`() {
        assertEquals("0082", WeightBucket.G250.code)
        assertEquals("0165", WeightBucket.G500.code)
        assertEquals("0198", WeightBucket.G600.code)
        assertEquals("0247", WeightBucket.G750.code)
        assertEquals("0330", WeightBucket.G1000.code)
    }

    @Test
    fun `exact weights map to their own bucket`() {
        WeightBucket.entries.forEach { bucket ->
            assertEquals(bucket, WeightBucket.nearestTo(bucket.grams))
        }
    }

    @Test
    fun `nearby weights round to the closest bucket`() {
        assertEquals(WeightBucket.G1000, WeightBucket.nearestTo(980))
        assertEquals(WeightBucket.G750, WeightBucket.nearestTo(760))
        assertEquals(WeightBucket.G500, WeightBucket.nearestTo(510))
        assertEquals(WeightBucket.G250, WeightBucket.nearestTo(240))
    }

    @Test
    fun `weights below the smallest bucket clamp to it`() {
        assertEquals(WeightBucket.G250, WeightBucket.nearestTo(1))
        assertEquals(WeightBucket.G250, WeightBucket.nearestTo(100))
    }

    @Test
    fun `weights above the largest bucket clamp to it`() {
        assertEquals(WeightBucket.G1000, WeightBucket.nearestTo(3000))
    }

    /** The two real midpoints in this set; documented to resolve upward. */
    @Test
    fun `exact midpoints round up to the heavier bucket`() {
        assertEquals(WeightBucket.G600, WeightBucket.nearestTo(550))
        assertEquals(WeightBucket.G750, WeightBucket.nearestTo(675))
    }

    @Test
    fun `just below a midpoint still rounds down`() {
        assertEquals(WeightBucket.G500, WeightBucket.nearestTo(549))
        assertEquals(WeightBucket.G600, WeightBucket.nearestTo(674))
    }

    @Test
    fun `rejects a non-positive weight`() {
        assertThrows(IllegalArgumentException::class.java) { WeightBucket.nearestTo(0) }
        assertThrows(IllegalArgumentException::class.java) { WeightBucket.nearestTo(-500) }
    }

    @Test
    fun `looks up buckets by code`() {
        assertEquals(WeightBucket.G600, WeightBucket.fromCode("0198"))
    }

    @Test
    fun `unknown code returns null`() {
        assertNull(WeightBucket.fromCode("0000"))
        assertNull(WeightBucket.fromCode(""))
    }
}
