package dev.benfranklin.itune.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.benfranklin.itune.App
import dev.benfranklin.itune.BuildConfig
import dev.benfranklin.itune.core.CoreException
import dev.benfranklin.itune.core.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** In-app updates from GitHub Releases (repo in BuildConfig.UPDATE_REPO). */
object Updater {
    sealed interface Result {
        data class Available(val info: UpdateInfo) : Result
        data object UpToDate : Result
        data object NeedsKey : Result
        data class Failed(val msg: String) : Result
    }

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val token = TokenStore.get()
        try {
            val info = App.core.checkUpdate(BuildConfig.UPDATE_REPO, BuildConfig.VERSION_NAME, token)
            App.prefs.updateNeedsKey.value = false
            if (info.newer) Result.Available(info) else Result.UpToDate
        } catch (e: CoreException.Auth) {
            // Repo went private (or the key stopped working): surface the small "Add access key" row only.
            if (token == null) App.prefs.updateNeedsKey.value = true
            Result.NeedsKey
        } catch (e: Exception) {
            Result.Failed(e.message ?: "$e")
        }
    }

    /** Downloads v4-x.y.z.apk and hands it to PackageInstaller. Returns an error message, or null when handed off. */
    suspend fun downloadAndInstall(ctx: Context, info: UpdateInfo, onProgress: (Float) -> Unit): String? {
        val asset = info.assets.firstOrNull { it.name.endsWith(".apk") } ?: return "No APK in this release"
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val dest = File(dir, asset.name)
        val poll = App.scope.launch {
            while (isActive) {
                App.core.downloadProgress("update")?.let { p -> withContext(Dispatchers.Main) { onProgress(p) } }
                delay(120)
            }
        }
        try {
            withContext(Dispatchers.IO) { App.core.downloadUpdate(asset.apiUrl, TokenStore.get(), dest.absolutePath) }
        } catch (e: Exception) {
            return "Download failed: ${e.message}"
        } finally {
            poll.cancel()
        }
        onProgress(1f)
        return withContext(Dispatchers.IO) { install(ctx, dest) }
    }

    private fun install(ctx: Context, apk: File): String? = runCatching {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val intent = Intent(ctx, InstallReceiver::class.java)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
        null
    }.getOrElse { "Install failed: ${it.message}" }
}

/** PackageInstaller callback: launches the system confirmation when needed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            else -> {}
        }
    }
}

/** Optional GitHub token for a private repo, encrypted with an Android Keystore AES-GCM key. */
object TokenStore {
    private const val ALIAS = "update-token-key"
    private val sp get() = App.instance.getSharedPreferences("secure", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return g.generateKey()
    }

    fun get(): String? = runCatching {
        val blob = sp.getString("t", null) ?: return null
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw.copyOfRange(12, raw.size)))
    }.getOrNull()

    fun set(token: String?) {
        if (token.isNullOrBlank()) {
            sp.edit().remove("t").apply()
            return
        }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(token.trim().toByteArray())
        sp.edit().putString("t", Base64.encodeToString(out, Base64.NO_WRAP)).apply()
    }

    fun has(): Boolean = sp.contains("t")
}
