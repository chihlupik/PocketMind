package com.example.androidaimanager

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class AiNotificationService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn?.let {
            val pkg = it.packageName
            val extras = it.notification.extras
            val title = extras.getString("android.title") ?: ""
            val text = extras.getString("android.text") ?: ""

            val file = File(applicationContext.filesDir, "notifications_log.json")
            val list = if (file.exists()) {
                try { JSONArray(file.readText()) } catch (_: Exception) { JSONArray() }
            } else {
                JSONArray()
            }

            val obj = JSONObject().apply {
                put("package", pkg)
                put("title", title)
                put("text", text)
                put("time", System.currentTimeMillis())
            }
            list.put(obj)

            val trimmedList = JSONArray()
            val start = maxOf(0, list.length() - 100)
            for (i in start until list.length()) {
                trimmedList.put(list.get(i))
            }
            file.writeText(trimmedList.toString())
        }
    }
}