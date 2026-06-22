package com.selxo.rougo

import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeBrowserScriptTest {
    @Test
    fun interceptScriptRoutesPlaylistUrlsThroughBridge() {
        val script = youtubeBrowserInterceptScript()

        assertTrue(script.contains("url.searchParams.get(\"list\")"))
        assertTrue(script.contains("window.RougoYoutube.openYoutubeUrl"))
    }
}
