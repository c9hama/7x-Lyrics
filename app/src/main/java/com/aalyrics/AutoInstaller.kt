package com.aalyrics

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/**
 * Re-installs this app through Android's own package installer, tagged with the Play Store as
 * installer, the way AAAD does. Real cars only show sideloaded car screens when the install looks
 * like it came through the normal installer for the Play Store, not from adb.
 */
object AutoInstaller {
    private const val PLAY_STORE = "com.android.vending"

    fun reinstallForAndroidAuto(activity: Activity) {
        // Android asks once to allow this app to install apps.
        if (!activity.packageManager.canRequestPackageInstalls()) {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
            return
        }
        // Our own installed APK, copied somewhere the installer can read via a content URI.
        val apk = File(activity.cacheDir, "updates/self.apk").apply { parentFile?.mkdirs() }
        File(activity.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)

        @Suppress("DEPRECATION") // still the only way to pass the installer package name
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, PLAY_STORE)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        activity.startActivity(intent)
    }
}
