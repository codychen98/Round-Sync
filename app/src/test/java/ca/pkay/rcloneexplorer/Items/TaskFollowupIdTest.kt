package ca.pkay.rcloneexplorer.Items

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskFollowupIdTest {

    @Test
    fun effectiveFollowupId_treatsNullNoneAndZeroAsAbsent() {
        assertNull(Task.effectiveFollowupId(null))
        assertNull(Task.effectiveFollowupId(Task.FOLLOWUP_NONE_ID))
        assertNull(Task.effectiveFollowupId(0L))
        assertNull(Task.effectiveFollowupId(-5L))
    }

    @Test
    fun effectiveFollowupId_keepsPositiveIds() {
        assertEquals(1L, Task.effectiveFollowupId(1L))
        assertEquals(42L, Task.effectiveFollowupId(42L))
    }
}
