package com.lanbrowserrelay.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiAccessPolicyTest {
    @Test fun acceptsOnlyTheExactNonEmptyCapability() {
        assertTrue(ApiAccessPolicy.isAuthorized("random-token", "random-token"))
        assertFalse(ApiAccessPolicy.isAuthorized("random-token", null))
        assertFalse(ApiAccessPolicy.isAuthorized("random-token", ""))
        assertFalse(ApiAccessPolicy.isAuthorized("random-token", "random-tokeN"))
        assertFalse(ApiAccessPolicy.isAuthorized("", ""))
    }
}
