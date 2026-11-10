package com.edureminder.quicktube.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromoGamesParseTest {
    @Test
    fun parsesEnabledGamesAndDefaultsTheStoreUrl() {
        val raw = """
            {"games": [
              {"id": "arrow", "name": "Arrow Puzzle", "package": "com.edureminder.arrow", "icon": "https://x/icon", "genre": "Puzzle", "extra": 1},
              {"id": "off", "name": "Hidden", "package": "com.example.off", "icon": "https://x/i", "enabled": false},
              {"id": "nameless", "name": "", "package": "com.example.n", "icon": "https://x/i"}
            ]}
        """.trimIndent()
        val games = PromoGamesConfig.parse(raw)!!
        assertEquals(listOf("arrow"), games.map { it.id })
        assertEquals("https://play.google.com/store/apps/details?id=com.edureminder.arrow", games[0].storeUrl)
        assertEquals("Puzzle", games[0].genre)
    }

    @Test
    fun emptyOrBrokenValuesAreRejected() {
        assertNull(PromoGamesConfig.parse(""))
        assertNull(PromoGamesConfig.parse("not json"))
    }
}
