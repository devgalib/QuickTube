package com.edureminder.quicktube.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignInWindowTest {
    @Test
    fun browserLooksLikeChromeNotAWebView() {
        val wv = "Mozilla/5.0 (Linux; Android 12; itel A70 Build/SP1A; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/130.0.6723.107 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 12; itel A70 Build/SP1A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.6723.107 Mobile Safari/537.36",
            browserUserAgent(wv),
        )
    }

    @Test
    fun signInPagesAreKeptAdsAreNot() {
        assertTrue(isSignInUrl("https://accounts.google.com/o/oauth2/v2/auth?client_id=1"))
        assertTrue(isSignInUrl("https://www.facebook.com/v19.0/dialog/oauth?app_id=1"))
        assertTrue(isSignInUrl("https://appleid.apple.com/auth/authorize?x=1"))
        assertTrue(isSignInUrl("https://www.dailymotion.com/signin"))
        assertTrue(isSignInUrl("https://example.com/social/google?next=/"))
        assertFalse(isSignInUrl("https://ads.example-network.com/click?id=42"))
        assertFalse(isSignInUrl("https://www.casino-bonus.example/landing"))
    }
}
