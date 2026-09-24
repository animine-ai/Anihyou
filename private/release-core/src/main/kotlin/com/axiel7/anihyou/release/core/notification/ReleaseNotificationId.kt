package com.axiel7.anihyou.release.core.notification

import java.security.MessageDigest

/**
 * Stable, process-independent Android notification id for one durable release event.
 *
 * The full event key is hashed so different accounts, streams and installments do not
 * accidentally reuse a visible notification id merely because their episode numbers match.
 */
object ReleaseNotificationId {
    fun fromEventKey(eventKey: String): Int {
        require(eventKey.isNotBlank()) { "event key must not be blank" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(eventKey.toByteArray(Charsets.UTF_8))
        var value = 0
        repeat(4) { index ->
            value = (value shl 8) or (digest[index].toInt() and 0xff)
        }
        value = value and Int.MAX_VALUE
        return if (value == 0) 1 else value
    }
}
