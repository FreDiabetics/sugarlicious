package jamorham.keks.util

import jamorham.keks.Curve

/** JamOrHam / xDrip libkeks packet buffers, translated to Kotlin. See LICENSES/xDrip-libkeks.md. */
class ByteArrayHashMap : HashMap<Int?, ByteArray?>() {
    fun mget(key: Int?): ByteArray = get(key) ?: ByteArray(Curve.FIELD_SIZE).also { put(key, it) }
}
