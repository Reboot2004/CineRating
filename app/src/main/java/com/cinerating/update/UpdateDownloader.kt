package com.cinerating.update

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Direct OkHttp download (no DownloadManager dependency — some OEM TVs disable it).
 * Streams to getExternalFilesDir(DOWNLOADS)/updates/, verifies SHA-256 when provided.
 */
class UpdateDownloader(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun download(
        info: UpdateInfo,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(info.apkUrl).get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("Empty body")
            val total = body.contentLength()
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                "updates"
            ).apply { mkdirs() }
            // Clean stale APKs first — old TVs have little storage.
            dir.listFiles()?.forEach { runCatching { it.delete() } }
            val outFile = File(dir, "cinerating-${info.versionCode}.apk")
            val digest = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                FileOutputStream(outFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        downloaded += n
                        withContext(Dispatchers.Main) { onProgress(downloaded, total) }
                    }
                    output.fd.sync()
                }
            }
            if (info.sha256.isNotBlank()) {
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(info.sha256.trim(), ignoreCase = true)) {
                    runCatching { outFile.delete() }
                    throw SecurityException("SHA-256 mismatch — update rejected")
                }
            }
            outFile
        }
    }
}
