package eu.kanade.tachiyomi.data.personal

import tachiyomi.core.common.preference.PreferenceStore
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.security.MessageDigest

/** Uses the app preference store, so these settings follow the normal preference backup. */
object PersonalStore {
    private val store get() = Injekt.get<PreferenceStore>()
    fun text(key: String, default: String = "") = store.getString("dtoon_$key", default)
    fun number(key: String, default: Int = 0) = store.getInt("dtoon_$key", default)
    fun long(key: String, default: Long = 0) = store.getLong("dtoon_$key", default)
    fun key(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
