package dev.benfranklin.itune

import android.content.Context
import java.io.File

/** Saves the stack trace of an uncaught exception; shown once (with Copy) on the next launch. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val txt = buildString {
                    append("v").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")  ")
                    append(android.os.Build.MODEL).append(" Android ").append(android.os.Build.VERSION.RELEASE).append('\n')
                    append("thread: ").append(t.name).append('\n')
                    append(android.util.Log.getStackTraceString(e))
                }
                file(ctx).writeText(txt.take(20_000))
                runCatching { File(ctx.filesDir, "crash_history.txt").appendText("\n\n==== " + java.util.Date() + "\n" + txt.take(8_000)) }
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Recent crash reports (newest last) for Settings › Diagnostics. */
    fun history(ctx: Context): String = runCatching { File(ctx.filesDir, "crash_history.txt").readText().takeLast(40_000) }.getOrDefault("")
    fun clearHistory(ctx: Context) { runCatching { File(ctx.filesDir, "crash_history.txt").delete() } }

    /** Returns and clears the saved crash, if any. */
    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val t = runCatching { f.readText() }.getOrNull()
        f.delete()
        return t?.takeIf { it.isNotBlank() }
    }
}
