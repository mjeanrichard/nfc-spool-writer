package ch.jeanrichard.nfcspoolwriter.domain.model

/**
 * The filament-length field accepts only five fixed codes — there is no arbitrary-gram encoding
 * (TAG_FORMAT_SPEC.md §9). Any real spool weight has to be rounded onto one of these buckets.
 *
 * The four-digit codes are not gram values; treat them as opaque lookup codes.
 */
enum class WeightBucket(val code: String, val grams: Int) {
    G250("0082", 250),
    G500("0165", 500),
    G600("0198", 600),
    G750("0247", 750),
    G1000("0330", 1000);

    companion object {
        /**
         * Rounds an arbitrary weight to the nearest bucket by absolute gram difference.
         *
         * Ties go to the **heavier** bucket: the buckets are nominal spool sizes, and a spool
         * sitting exactly between two of them is more likely the larger size partially used than
         * the smaller size overfilled. The only exact midpoints in this set are 550 g (500/600)
         * and 675 g (600/750), so this rule is narrow but needs to be deterministic.
         */
        fun nearestTo(grams: Int): WeightBucket {
            require(grams > 0) { "weight must be positive, was $grams g" }
            return entries.minWith(
                compareBy({ kotlin.math.abs(it.grams - grams) }, { -it.grams })
            )
        }

        fun fromCode(code: String): WeightBucket? = entries.firstOrNull { it.code == code }
    }
}
