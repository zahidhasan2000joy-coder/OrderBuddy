package com.example.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "NotificationJobService"

class NotificationJobService : JobService() {

  private val jobScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var activeJob: Job? = null

  override fun onStartJob(params: JobParameters?): Boolean {
    val appContext = applicationContext
    activeJob = jobScope.launch {
      var success = false
      try {
        success = NotificationSyncManager.fetchAndShowPendingNotifications(appContext)
        NotificationSyncManager.ensureFirebaseInitialized(appContext)
        if (Firebase.auth.currentUser != null) {
          NotificationSyncManager.scheduleNextAlarm(appContext)
          NotificationSyncManager.scheduleQuickPollJob(appContext)
        }
      } catch (e: Throwable) {
        Log.w(TAG, "NotificationJobService failed: ${e.message}")
      } finally {
        val isPeriodic = params?.jobId == NotificationSyncManager.JOB_ID_PERIODIC
        jobFinished(params, !success && !isPeriodic)
      }
    }
    return true
  }

  override fun onStopJob(params: JobParameters?): Boolean {
    activeJob?.cancel()
    return true
  }

  override fun onDestroy() {
    jobScope.cancel()
    super.onDestroy()
  }
}
