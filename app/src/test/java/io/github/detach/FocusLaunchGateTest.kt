package io.github.detach

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FocusLaunchGateTest {
    @Test fun `handoff accepts only the selected package`() {
        FocusLaunchGate.clear()
        FocusLaunchGate.begin("com.whatsapp")
        assertTrue(FocusLaunchGate.awaiting())
        assertTrue(FocusLaunchGate.accepts("com.whatsapp"))
        assertFalse(FocusLaunchGate.accepts("com.android.chrome"))
        FocusLaunchGate.clear()
        assertFalse(FocusLaunchGate.awaiting())
    }
}
