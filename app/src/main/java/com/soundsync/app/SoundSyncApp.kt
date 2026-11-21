package com.soundsync.app

import android.app.Application
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class SoundSyncApp : Application() {
    override fun onCreate() {
        super.onCreate()
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("SoundSyncCrash", "FATAL CRASH in thread ${thread.name}", throwable)
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val dumpFile = File(filesDir, "crash_dump.txt")
                dumpFile.writeText(sw.toString())
            } catch (e: Exception) {
                // Ignore secondary errors
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
