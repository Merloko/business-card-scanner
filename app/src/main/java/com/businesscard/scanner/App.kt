package com.businesscard.scanner

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Process
import androidx.appcompat.app.AppCompatDelegate
import com.businesscard.scanner.ui.ReminderReceiver
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        applyNightMode()
        createNotificationChannels()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val crashText = sw.toString()
                val safeText = crashText.take(4096)

                // Internal (for in-app display on next launch)
                crashFile().writeText(safeText)

            } catch (_: Exception) {}

            defaultHandler?.uncaughtException(thread, throwable)
            Process.killProcess(Process.myPid())
        }
    }

    private fun applyNightMode() {
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val mode = prefs.getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ReminderReceiver.CHANNEL_ID,
                ReminderReceiver.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    fun crashFile(): File = File(filesDir, "last_crash.txt")
}
