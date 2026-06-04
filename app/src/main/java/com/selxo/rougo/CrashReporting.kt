package com.selxo.rougo

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class RougoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        RougoForegroundTracker.register(this)
    }
}

object RougoForegroundTracker {
    @Volatile
    private var startedActivityCount = 0

    val isForeground: Boolean
        get() = startedActivityCount > 0

    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount += 1
                clearCompletedDownloadNotifications(activity)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}

object CrashReporter {
    private const val TAG = "RougoCrash"
    private const val CRASH_FILE_NAME = "last_crash.txt"
    private const val HANDLED_FILE_NAME = "handled_errors.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true

        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrash(appContext, thread, throwable)
                Log.e(TAG, "Uncaught exception on ${thread.name}", throwable)
            } catch (loggingError: Throwable) {
                loggingError.printStackTrace()
            }

            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun readLastCrash(context: Context): String? {
        return try {
            crashFile(context).takeIf { it.exists() && it.length() > 0L }?.readText()
        } catch (e: Exception) {
            null
        }
    }

    fun clearLastCrash(context: Context) {
        try { crashFile(context).delete() } catch (e: Exception) {}
    }

    fun recordHandled(context: Context, area: String, throwable: Throwable) {
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
            handledFile(context).appendText(
                buildString {
                    appendLine("[$timestamp] $area")
                    appendLine(stackTraceToString(throwable))
                    appendLine()
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not write handled error", e)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo?.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo?.versionCode?.toLong()
        }

        crashFile(context).writeText(
            buildString {
                appendLine("朗語 crash report")
                appendLine("Time: $timestamp")
                appendLine("App: ${packageInfo?.versionName ?: "unknown"} ($versionCode)")
                appendLine("Package: ${context.packageName}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine("Thread: ${thread.name} / ${thread.id}")
                appendLine()
                appendLine(stackTraceToString(throwable))
            }
        )
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val writer = StringWriter()
        throwable.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }

    private fun crashFile(context: Context): File = File(context.filesDir, CRASH_FILE_NAME)

    private fun handledFile(context: Context): File = File(context.filesDir, HANDLED_FILE_NAME)
}
