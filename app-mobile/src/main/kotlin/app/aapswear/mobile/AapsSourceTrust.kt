package app.aapswear.mobile

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.edit
import java.security.MessageDigest

internal data class AapsSourceBinding(
    val packageName: String,
    val signingCertificateSha256: String,
)

internal enum class AapsSourceTrust {
    VERIFIED,
    LEGACY_COMPATIBILITY,
    REJECTED_NOT_CONFIGURED,
    REJECTED_WRONG_SENDER,
    REJECTED_CERTIFICATE_CHANGED,
}

internal object AapsSourceTrustPolicy {
    const val OFFICIAL_PACKAGE = "info.nightscout.androidaps"

    fun isAllowedPackageName(packageName: String): Boolean = packageName == OFFICIAL_PACKAGE

    fun evaluate(
        binding: AapsSourceBinding?,
        sentFromPackage: String?,
        sentFromUidPackages: Set<String>,
        installedCertificateSha256: String?,
    ): AapsSourceTrust {
        binding ?: return AapsSourceTrust.REJECTED_NOT_CONFIGURED
        if (installedCertificateSha256 != binding.signingCertificateSha256) {
            return AapsSourceTrust.REJECTED_CERTIFICATE_CHANGED
        }
        if (sentFromPackage == null && sentFromUidPackages.isEmpty()) {
            return AapsSourceTrust.LEGACY_COMPATIBILITY
        }
        return if (sentFromPackage == binding.packageName || binding.packageName in sentFromUidPackages) {
            AapsSourceTrust.VERIFIED
        } else {
            AapsSourceTrust.REJECTED_WRONG_SENDER
        }
    }
}

internal class AapsSourceBindingStore(
    private val context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun read(): AapsSourceBinding? {
        val packageName = preferences.getString(KEY_PACKAGE, null)?.takeIf { it.isNotBlank() } ?: return null
        val fingerprint = preferences.getString(KEY_CERTIFICATE, null)?.takeIf { it.isNotBlank() } ?: return null
        return AapsSourceBinding(packageName, fingerprint)
    }

    fun bind(packageName: String): AapsSourceBinding? {
        val normalized = packageName.trim()
        if (!AapsSourceTrustPolicy.isAllowedPackageName(normalized)) return null
        val fingerprint = signingCertificateSha256(normalized) ?: return null
        return AapsSourceBinding(normalized, fingerprint).also { binding ->
            preferences.edit {
                putString(KEY_PACKAGE, binding.packageName)
                putString(KEY_CERTIFICATE, binding.signingCertificateSha256)
            }
        }
    }

    fun currentCertificate(binding: AapsSourceBinding): String? = signingCertificateSha256(binding.packageName)

    @Suppress("DEPRECATION")
    private fun signingCertificateSha256(packageName: String): String? {
        val packageInfo =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                } else {
                    context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                }
            }.getOrNull() ?: return null
        val signature =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                packageInfo.signatures?.firstOrNull()
            } ?: return null
        return MessageDigest
            .getInstance("SHA-256")
            .digest(signature.toByteArray())
            .joinToString(":") { "%02X".format(it) }
    }

    companion object {
        private const val PREFERENCES = "aaps_source_security"
        private const val KEY_PACKAGE = "package"
        private const val KEY_CERTIFICATE = "certificateSha256"
    }
}
