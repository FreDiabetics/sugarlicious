package app.aapswear.mobile

import java.net.URI

internal object LocalNetworkAccessPolicy {
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    private const val ANDROID_17_API = 37

    fun needsPermission(
        sdkInt: Int,
        enabled: Boolean,
        baseUrl: String,
        permissionGranted: Boolean,
    ): Boolean =
        sdkInt >= ANDROID_17_API &&
            enabled &&
            !permissionGranted &&
            isLocalDestination(baseUrl)

    internal fun isLocalDestination(baseUrl: String): Boolean {
        val host =
            runCatching { URI(baseUrl.trim()).host?.lowercase()?.removeSurrounding("[", "]") }
                .getOrNull() ?: return false
        if (host == "localhost" || host.endsWith(".local")) return true
        if (
            host == "::1" ||
            host.startsWith("fe8") ||
            host.startsWith("fe9") ||
            host.startsWith("fea") ||
            host.startsWith("feb") ||
            host.startsWith("fc") ||
            host.startsWith("fd")
        ) {
            return true
        }

        val octets = host.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            octets[0] == 127 ||
            (octets[0] == 169 && octets[1] == 254) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
    }
}
