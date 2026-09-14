package com.cinerating.update

import android.content.Context
import android.content.SharedPreferences
import com.cinerating.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Prompted update check: launch + max once per 24h.
 * No auth, no rate limits — plain HTTPS GET of a tiny static JSON on Pages.
 */
class UpdateChecker(
    private val context: Context,
    private val versionUrl: String = BuildConfig.UPDATE_VERSION_URL
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(UpdateInfo::class.java)

    suspend fun check(force: Boolean = false): UpdateInfo? = withContext(Dispatchers.IO) {
        if (!force && !shouldCheck()) return@withContext null
        val info = fetch() ?: return@withContext null
        prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        if (info.versionCode > BuildConfig.VERSION_CODE && info.apkUrl.startsWith("https://")) {
            info
        } else {
            null
        }
    }

    fun lastCheckText(): String {
        val ts = prefs.getLong(KEY_LAST_CHECK, 0L)
        if (ts == 0L) return "never"
        val mins = ((System.currentTimeMillis() - ts) / 60000).toInt()
        return if (mins < 1) "just now" else if (mins < 60) "${mins}m ago"
        else "${mins / 60}h ago"
    }

    private fun shouldCheck(): Boolean {
        val last = prefs.getLong(KEY_LAST_CHECK, 0L)
        return System.currentTimeMillis() - last > CHECK_INTERVAL_MS
    }

    private fun fetch(): UpdateInfo? {
        return runCatching {
            val req = Request.Builder().url(versionUrl).get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body?.string() ?: return null
                adapter.fromJson(body)
            }
        }.getOrNull()
    }

    companion object {
        private const val PREFS = "cinerating_updates"
        private const val KEY_LAST_CHECK = "last_check_ms"
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    }
}
