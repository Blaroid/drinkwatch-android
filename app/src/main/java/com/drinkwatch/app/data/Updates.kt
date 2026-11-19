package com.drinkwatch.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.drinkwatch.app.BuildConfig
import kotlinx.coroutines.CancellationException
import java.io.File
import java.security.MessageDigest

data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
)

/** What an APK claims about itself: the two things the installer checks before it replaces anything. */
data class ApkIdentity(
    val versionCode: Int,
    /** Sorted, comma-joined SHA-256 of the signing certificates; null when unreadable. */
    val signerSha256: String?,
)

sealed interface InstallVerdict {
    data object Installable : InstallVerdict
    data class Rejected(val message: String) : InstallVerdict
}

/**
 * Decide whether a downloaded APK may be handed to the system installer.
 *
 * The publisher is supposed to refuse a debug-signed or stale APK, but the device is
 * the last gate: the installer answers a foreign key with INSTALL_FAILED_UPDATE_INCOMPATIBLE
 * and leaves the app stuck, and the uninstall that follows wipes the token, watchlist,
 * basket and alerts (allowBackup is off). Refusing here names the cause instead.
 *
 * A file we cannot inspect at all is left to the installer: the checks below are the
 * ones that would otherwise fail late and confusingly, so they must not invent a
 * failure when the archive simply could not be read.
 */
fun installVerdict(
    installed: ApkIdentity,
    candidate: ApkIdentity,
    advertisedCode: Int,
): InstallVerdict = when {
    candidate.versionCode < advertisedCode -> InstallVerdict.Rejected(
        "The published APK is older than its version.json — not installing it",
    )
    candidate.versionCode <= installed.versionCode -> InstallVerdict.Rejected(
        "The published APK is not newer than the installed app — not installing it",
    )
    installed.signerSha256 != null && candidate.signerSha256 != null &&
        !installed.signerSha256.equals(candidate.signerSha256, ignoreCase = true) -> InstallVerdict.Rejected(
        "The published APK is signed with a different key — do not uninstall, that would erase your data",
    )
    else -> InstallVerdict.Installable
}

/**
 * A stable name for a set of signing certificates: SHA-256 each, sorted, then joined.
 * Order does not matter (APK Signature Scheme v3 can reorder a rotation), and the
 * digest is what `apksigner verify --print-certs` reports, so logs can be compared.
 */
fun signerFingerprint(certificates: List<ByteArray>): String? =
    certificates
        .takeIf { it.isNotEmpty() }
        ?.map { certificate ->
            MessageDigest.getInstance("SHA-256")
                .digest(certificate)
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
        ?.sorted()
        ?.joinToString(",")

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
            when (val verdict = verdictFor(target, update.versionCode)) {
                is InstallVerdict.Rejected -> {
                    target.delete()
                    state = UpdateState.Error(verdict.message)
                }
                InstallVerdict.Installable -> {
                    state = UpdateState.ReadyToInstall(target)
                    install(target)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            state = UpdateState.Error(error.message ?: "Download failed")
        }
    }

    /** Compare the downloaded file with the installed package; unknown identities fall through. */
    private fun verdictFor(file: File, advertisedCode: Int): InstallVerdict {
        val installed = runCatching { identityOf(packageInfo(context.packageName)) }.getOrNull()
            ?: return InstallVerdict.Installable
        val candidate = runCatching { identityOf(archiveInfo(file)) }.getOrNull()
            ?: return InstallVerdict.Installable
        return installVerdict(installed, candidate, advertisedCode)
    }

    /** Signing certificates need the newer flag from P; below it the deprecated array is all there is. */
    @Suppress("DEPRECATION")
    private val signingFlags: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    @Suppress("DEPRECATION")
    private fun packageInfo(name: String): PackageInfo? =
        context.packageManager.getPackageInfo(name, signingFlags)

    @Suppress("DEPRECATION")
    private fun archiveInfo(file: File): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(file.absolutePath, signingFlags)

    @Suppress("DEPRECATION")
    private fun identityOf(info: PackageInfo?): ApkIdentity? {
        if (info == null) return null
        val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners?.map { it.toByteArray() }
        } else {
            info.signatures?.map { it.toByteArray() }
        } ?: emptyList()
        return ApkIdentity(
            versionCode = PackageInfoCompat.getLongVersionCode(info).toInt(),
            signerSha256 = signerFingerprint(certificates),
        )
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
