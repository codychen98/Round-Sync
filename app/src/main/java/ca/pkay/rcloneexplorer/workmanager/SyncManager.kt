package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import ca.pkay.rcloneexplorer.Items.Task
import ca.pkay.rcloneexplorer.Items.Trigger
import java.util.Random

class SyncManager(private var mContext: Context) {

    companion object {
        fun uniqueWorkNameForTask(taskId: Long): String = "sync_task_$taskId"

        fun isActiveWorkState(state: WorkInfo.State): Boolean =
            state == WorkInfo.State.RUNNING || state == WorkInfo.State.ENQUEUED
    }
    fun queue(trigger: Trigger) {
        queue(trigger.triggerTarget)
    }

    fun queue(task: Task) {
        queue(task.id)
    }

    fun queue(taskID: Long) {
        val uploadWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()

        val data = Data.Builder()
        data.putLong(SyncWorker.TASK_ID, taskID)

        uploadWorkRequest.setInputData(data.build())
        uploadWorkRequest.addTag(taskID.toString())
        val request = uploadWorkRequest.build()
        WorkManager.getInstance(mContext).enqueueUniqueWork(
            uniqueWorkNameForTask(taskID),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun queueEphemeral(task: Task) {

        task.id = Random().nextLong()
        val uploadWorkRequest = OneTimeWorkRequestBuilder<SyncWorker>()

        val data = Data.Builder()
        data.putString(SyncWorker.TASK_EPHEMERAL, task.asJSON().toString())

        uploadWorkRequest.setInputData(data.build())
        uploadWorkRequest.addTag(task.id.toString())
        work(uploadWorkRequest.build())
    }

    private fun work(request: WorkRequest) {
        WorkManager.getInstance(mContext)
            .enqueue(request)
    }

    fun cancel() {
        WorkManager.getInstance(mContext)
            .cancelAllWork()
    }

    fun isTaskActive(taskId: Long): Boolean {
        return try {
            val infos = WorkManager.getInstance(mContext)
                .getWorkInfosByTag(taskId.toString())
                .get()
            infos.any { isActiveWorkState(it.state) }
        } catch (e: Exception) {
            Log.w("SyncManager", "Failed to query work state for task $taskId", e)
            false
        }
    }

    fun cancel(taskId: Long) {
        val wm = WorkManager.getInstance(mContext)
        wm.cancelAllWorkByTag(taskId.toString())
        wm.cancelUniqueWork(uniqueWorkNameForTask(taskId))
    }

    fun cancel(tag: String) {
        tag.toLongOrNull()?.let { cancel(it) } ?: run {
            WorkManager.getInstance(mContext).cancelAllWorkByTag(tag)
        }
    }
}