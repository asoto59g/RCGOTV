package com.abcgeomag.rcgotv

import androidx.compose.ui.unit.LayoutDirection

enum class RemoteQuickAction {
    BACK, HOME, NUMBER_PAD
}

object RemoteUiSpec {
    val volumeControls = listOf("Vol +", "Mute", "Vol -")
    val channelControls = listOf("CH-LIST", "Ch +", "Ch -")
    val quickActions = listOf("Back", "Home", "123")
    val bottomNavigation = listOf("Remote", "Apps", "Cast", "Settings")

    val coordinateLayoutDirection = LayoutDirection.Ltr
    val homeX = 100
    val playX = 154
    val rockerSegmentHeightDp = 32

    val quickActionCommands = listOf(
        RemoteQuickAction.BACK,
        RemoteQuickAction.HOME,
        RemoteQuickAction.NUMBER_PAD
    )

    fun isActionable(action: RemoteQuickAction): Boolean =
        when (action) {
            RemoteQuickAction.BACK,
            RemoteQuickAction.HOME,
            RemoteQuickAction.NUMBER_PAD -> true
        }
}
