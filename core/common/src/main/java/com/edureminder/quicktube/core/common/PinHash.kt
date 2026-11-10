package com.edureminder.quicktube.core.common

import java.security.MessageDigest
import java.security.SecureRandom

/** The vault PIN is kept as "sha256:<salt>:<hash>", never as the digits themselves. */
object PinHash {
    private const val PREFIX = "sha256:"

    fun hash(pin: String, salt: String = newSalt()): String = PREFIX + salt + ":" + digest(salt, pin)

    /** True when [pin] matches [stored]; a PIN saved before hashing (plain digits) still opens the vault. */
    fun matches(pin: String, stored: String?): Boolean {
        if (stored == null) return false
        if (!isHashed(stored)) return MessageDigest.isEqual(pin.toByteArray(), stored.toByteArray())
        val (salt, hash) = stored.removePrefix(PREFIX).split(':', limit = 2).takeIf { it.size == 2 } ?: return false
        return MessageDigest.isEqual(digest(salt, pin).toByteArray(), hash.toByteArray())
    }

    fun isHashed(stored: String) = stored.startsWith(PREFIX)

    private fun newSalt(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private fun digest(salt: String, pin: String): String {
        var bytes = (salt + pin).toByteArray()
        // A few thousand rounds: a 4-digit PIN can't be made strong, but a copied settings file shouldn't give it away at once.
        repeat(5000) { bytes = MessageDigest.getInstance("SHA-256").digest(bytes) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
