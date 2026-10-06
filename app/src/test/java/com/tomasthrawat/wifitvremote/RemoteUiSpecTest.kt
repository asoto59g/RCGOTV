package com.abcgeomag.rcgotv

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteUiSpecTest {
    @Test
    fun remoteControlLabels_matchReferenceLayout() {
        assertEquals(
            listOf("Vol +", "Mute", "Vol -"),
            RemoteUiSpec.volumeControls
        )
        assertEquals(
            listOf("CH-LIST", "Ch +", "Ch -"),
            RemoteUiSpec.channelControls
        )
        assertEquals(
            listOf("Back", "Home", "123"),
            RemoteUiSpec.quickActions
        )
        assertEquals(
            listOf("Remote", "Apps", "Cast", "Settings"),
            RemoteUiSpec.bottomNavigation
        )
    }

    @Test
    fun appShortcuts_openGooglePlayDetailsAndHaveDistinctLogos() {
        assertEquals(
            listOf(
                "market://details?id=com.netflix.ninja" to AppLogo.NETFLIX,
                "market://details?id=com.google.android.youtube.tv" to AppLogo.YOUTUBE,
                "market://details?id=com.amazon.amazonvideo.livingroom" to AppLogo.PRIME_VIDEO,
                "market://details?id=com.spotify.tv.android" to AppLogo.SPOTIFY,
                "market://details?id=com.xuper.tv" to AppLogo.XUPER_TV,
                "market://details?id=com.stremio.one" to AppLogo.STREMIO
            ),
            tvApps.map { it.playStoreLink to it.logo }
        )
    }
}
