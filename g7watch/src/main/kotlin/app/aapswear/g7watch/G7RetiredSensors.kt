package app.aapswear.g7watch

import android.annotation.SuppressLint
import android.content.Context
import java.util.Locale

/** Address deny-list only: no old session, code, key or reconnect task is retained. */
internal class G7RetiredSensors(
    context: Context,
) {
    private val preferences = context.applicationContext.getSharedPreferences("g7_retired_addresses", Context.MODE_PRIVATE)

    fun contains(address: String?): Boolean =
        address != null && address.uppercase(Locale.ROOT) in preferences.getStringSet("addresses", emptySet()).orEmpty()

    @Synchronized
    @SuppressLint("UseKtx") // KTX edit discards commit's result; detachment must fail if persistence fails.
    fun retire(addresses: Set<String>) {
        val retired = preferences.getStringSet("addresses", emptySet()).orEmpty() + addresses.map { it.uppercase(Locale.ROOT) }
        check(preferences.edit().putStringSet("addresses", retired).commit()) { "G7 exclusion list could not be persisted" }
    }
}
