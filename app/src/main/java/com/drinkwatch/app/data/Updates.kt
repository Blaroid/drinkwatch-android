package com.drinkwatch.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.drinkwatch.app.BuildConfig
import kotlinx.coroutines.CancellationException
import java.io.File

data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val progress: Float?) : UpdateState
    data class ReadyToInstall(val file: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * Self-update for a sideloaded app.
 *
 * The APK is served by the API at /app/drinkwatch.apk and the metadata at
 * /app/version.json, so the app can check, download and hand the file to the
 * system installer without a store in the loop.
 */
class UpdateController(private val context: Context) {

    private val prefs = ServiceLocator.prefs(context)
    private val api = ServiceLocator.api(context)

    var state: UpdateState by mutableStateOf(UpdateState.Idle)
        private set

    /** Set when a launch-time check finds a newer build and the user has not deferred it. */
    var showPrompt: Boolean by mutableStateOf(false)
        private set

    val currentVersion: String get() = BuildConfig.VERSION_NAME
    val currentCode: Int get() = BuildConfig.VERSION_CODE

    suspend fun check(silent: Boolean) {
        if (!silent) state = UpdateState.Checking
        try {
            val latest = api.latestAppVersion()
            state = if (latest.versionCode > currentCode) {
                UpdateState.Available(latest)
            } else {
                UpdateState.UpToDate
            }
            val available = state as? UpdateState.Available
            if (available != null && available.update.versionCode != prefs.skippedUpdateCode) {
                showPrompt = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            state = UpdateState.Error(error.message ?: "Could not check for updates")
        }
    }

    fun dismissPrompt(defer: Boolean) {
        val available = state as? UpdateState.Available
        if (defer && available != null) prefs.skippedUpdateCode = available.update.versionCode
        showPrompt = false
    }

    suspend fun downloadAndInstall(update: AppUpdate) {
        if (!canInstallPackages()) {
            state = UpdateState.Error("Allow DrinkWatch to install unknown apps, then try again")
            openInstallPermissionSettings()
            return
        }
        state = UpdateState.Downloading(0f)
        val target = File(context.cacheDir, "updates/drinkwatch-" + update.versionCode + ".apk")
        try {
            api.downloadApk(update.apkUrl, target) { progress -> state = UpdateState.Downloading(progress) }
            state = UpdateState.ReadyToInstall(target)
            install(target)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            state = UpdateState.Error(error.message ?: "Download failed")
        }
    }

    fun install(file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { state = UpdateState.Error("No package installer available") }
    }

    fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + context.packageName),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    fun reset() {
        state = UpdateState.Idle
    }
}
