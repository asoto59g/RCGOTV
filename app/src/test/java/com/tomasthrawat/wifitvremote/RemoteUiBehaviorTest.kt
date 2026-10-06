package com.abcgeomag.rcgotv

import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteUiBehaviorTest {
    @Test
    fun referenceCanvas_isFixedLtrAndKeepsReferenceHomeAndPlayCoordinates() {
        assertEquals(LayoutDirection.Ltr, RemoteUiSpec.coordinateLayoutDirection)
        assertEquals(100, RemoteUiSpec.homeX)
        assertEquals(154, RemoteUiSpec.playX)
    }

    @Test
    fun quickActions_areActionableAndHaveExpectedCommands() {
        assertEquals(
            listOf(
                RemoteQuickAction.BACK,
                RemoteQuickAction.HOME,
                RemoteQuickAction.NUMBER_PAD
            ),
            RemoteUiSpec.quickActionCommands
        )
        assertTrue(RemoteUiSpec.quickActionCommands.all(RemoteUiSpec::isActionable))
    }

    @Test
    fun rockerSegments_useReferenceInteractiveHeight() {
        assertEquals(32, RemoteUiSpec.rockerSegmentHeightDp)
    }
}
