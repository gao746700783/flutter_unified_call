package dev.unifiedcall.flutter_unified_call

import android.app.Notification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationFallbackTest {
    @Test fun callStylePolicyRejectionRetriesGenericNotification() {
        val original = Notification(); val generic = Notification()
        val posted = mutableListOf<Notification>()
        val degraded = postCallStyleWithFallback(original, { generic }) {
            posted.add(it)
            if (it === original) throw IllegalArgumentException("CallStyle notifications must either be for a foreground Service or use a fullScreenIntent.")
        }
        assertTrue(degraded)
        assertEquals(listOf(original, generic), posted)
    }
    @Test fun unrelatedErrorsAndFailedFallbackPropagate() {
        assertThrows(SecurityException::class.java) {
            postCallStyleWithFallback(Notification(), { fail("Must not create fallback"); Notification() }) { throw SecurityException("Denied") }
        }
        var attempts = 0
        assertThrows(IllegalStateException::class.java) {
            postCallStyleWithFallback(Notification(), { Notification() }) {
                attempts++
                if (attempts == 1) throw IllegalArgumentException("CallStyle notifications must use a foreground service or fullScreenIntent")
                throw IllegalStateException("Fallback failed")
            }
        }
        assertEquals(2, attempts)
    }
}
