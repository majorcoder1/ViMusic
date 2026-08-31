package it.vfsfitvnm.vimusic.service

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * Decides which apps may browse the media library.
 *
 * [android.service.media.MediaBrowserService] is exported and unprotected by permission, so any
 * installed app can bind to it. Checking the caller's *package name* is not enough: package names
 * are unique only among installed apps, so on a device without Android Auto anything can claim to
 * be `com.google.android.projection.gearhead` and be believed. The browse tree is the whole
 * listening history, and the session token that comes with it grants playback control -- neither
 * should be handed out on a name alone.
 *
 * Identity is therefore taken from the signing certificate, which cannot be claimed, plus the
 * caller's uid and system status, which the framework supplies rather than the caller.
 */
internal object CallerValidator {
    private const val TAG = "CallerValidator"

    /** Certificates known to belong to Android Auto, lowercase hex SHA-256. */
    private val TrustedCertificates = setOf(
        // Android Auto, release
        "fdb00c43dbde8b51cb312aa81d3b5fa17713adb94b28f598d77f8eb89daceedf",
        // Android Auto, development build
        "1975b2f17177bc89a5dff31f9e64a6cae281a53dc1d1d59b1d147fe1c82afa00"
    )

    private val TrustedPackages = setOf(
        "com.google.android.projection.gearhead",
        "com.google.android.wearable.app",
        "com.google.android.googlequicksearchbox",
        "com.android.bluetooth",
        "com.google.android.bluetooth"
    )

    fun isTrusted(packageManager: PackageManager, packageName: String): Boolean {
        val info = runCatching {
            packageManager.getApplicationInfo(packageName, 0)
        }.getOrElse {
            Log.w(TAG, "rejecting $packageName: not installed")
            return false
        }

        // Preinstalled system components are as trustworthy as the platform itself, and this is
        // what covers a car head unit or Bluetooth stack baked into the image.
        val isSystem = info.flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        if (isSystem && packageName in TrustedPackages) return true

        val certificates = signingCertificates(packageManager, packageName)
        if (certificates.any { it in TrustedCertificates }) return true

        // Logged rather than swallowed: if a genuine car is ever turned away, this line names the
        // package and the certificate to add, which is otherwise almost impossible to work out
        // from a head unit that simply shows nothing.
        Log.w(
            TAG,
            "rejecting $packageName (system=$isSystem) certificates=$certificates"
        )
        return false
    }

    private fun signingCertificates(
        packageManager: PackageManager,
        packageName: String
    ): List<String> = runCatching {
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageManager.getPackageInfo(
                packageName,
                PackageManager.GET_SIGNING_CERTIFICATES
            ).signingInfo ?: return emptyList()

            if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }

        signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }.getOrDefault(emptyList())
}
