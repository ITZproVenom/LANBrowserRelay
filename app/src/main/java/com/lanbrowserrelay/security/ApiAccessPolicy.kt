package com.lanbrowserrelay.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Constant-time comparison for the per-server capability embedded in the trusted UI. */
object ApiAccessPolicy {
    fun isAuthorized(expectedToken: String, suppliedToken: String?): Boolean {
        if (expectedToken.isEmpty() || suppliedToken.isNullOrEmpty()) return false
        return MessageDigest.isEqual(
            expectedToken.toByteArray(StandardCharsets.UTF_8),
            suppliedToken.toByteArray(StandardCharsets.UTF_8)
        )
    }
}
