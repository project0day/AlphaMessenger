package com.alphamessenger.calls.service

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** "Remind me" from the incoming-call screen: posts the call-back notification when it is due. */
class CallReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val chat = inputData.getString("chat").orEmpty()
        if (chat.isBlank()) return Result.success()
        CallNotificationHelper(applicationContext).showCallReminder(
            chat,
            inputData.getString("name").orEmpty(),
            inputData.getBoolean("video", false),
            inputData.getString("avatar").orEmpty(),
        )
        return Result.success()
    }
}
