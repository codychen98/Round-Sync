package ca.pkay.rcloneexplorer.workmanager

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncManagerTest {

    @Test
    fun uniqueWorkNameForTask_usesStablePrefixAndId() {
        assertEquals("sync_task_42", SyncManager.uniqueWorkNameForTask(42L))
        assertEquals("sync_task_0", SyncManager.uniqueWorkNameForTask(0L))
    }

    @Test
    fun isActiveWorkState_matchesRunningAndEnqueuedOnly() {
        assertTrue(SyncManager.isActiveWorkState(WorkInfo.State.RUNNING))
        assertTrue(SyncManager.isActiveWorkState(WorkInfo.State.ENQUEUED))
        assertFalse(SyncManager.isActiveWorkState(WorkInfo.State.SUCCEEDED))
        assertFalse(SyncManager.isActiveWorkState(WorkInfo.State.FAILED))
        assertFalse(SyncManager.isActiveWorkState(WorkInfo.State.CANCELLED))
    }
}
