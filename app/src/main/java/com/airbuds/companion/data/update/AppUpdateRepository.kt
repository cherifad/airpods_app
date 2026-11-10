package com.airbuds.companion.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import com.airbuds.companion.BuildConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Checks GitHub Releases and downloads the APK inside the app.
 *
 * The app never opens a browser: the JSON metadata and the asset are fetched
 * with [HttpURLConnection] and the APK is handed to the package installer via
 * a [FileProvider] URI.
 */
class AppUpdateRepository(private val context: Context) {

    suspend fun latestUpdate(): AppRelease? = withContext(Dispatchers.IO) {
        val body = httpGet(LATEST_RELEASE_URL)
        if (body.isBlank()) return@withContext null
        val release = AppReleaseParser.parse(body) ?: return@withContext null
        val current = AppVersion.parse(BuildConfig.VERSION_NAME) ?: return@withContext null
        release.takeIf { it.version > current }
    }

    /**
     * Downloads [release] to the app's external files directory.
     *
     * [onProgress] receives 0–100; a partial download is removed on failure.
     */
    suspend fun download(
        release: AppRelease,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val directory = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.cacheDir
        val target = File(directory, "buds-companion-${release.version}.apk")
        val partial = File(directory, "${target.name}.part")

        val connection = (URL(release.asset.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
        }

        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Download failed (HTTP ${connection.responseCode})")
            }
            val total = release.asset.size.takeIf { it > 0 } ?: connection.contentLengthLong
            partial.delete()
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }

            release.asset.sha256?.let { expected ->
                val actual = sha256(partial)
                if (!actual.equals(expected, ignoreCase = true)) {
                    throw IOException("Downloaded APK failed its SHA-256 check")
                }
            }

            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            onProgress(100)
            target
        } catch (error: Exception) {
            partial.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the Android setting that allows this app to install packages. */
    fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Starts the system package installer for [file].
     *
     * Returns false when the user has not allowed installs from this source.
     */
    fun install(file: File): Boolean {
        if (!file.exists() || !canInstall()) return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return true
    }

    private fun httpGet(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            when (connection.responseCode) {
                404 -> ""
                in 200..299 -> connection.inputStream.bufferedReader().use { it.readText() }
                else -> throw IOException("Update check failed (HTTP ${connection.responseCode})")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DOWNLOAD_BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val REPOSITORY = "cherifad/airpods_app"
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"

        private const val APK_MIME = "application/vnd.android.package-archive"
        private const val USER_AGENT = "BudsCompanion"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val DOWNLOAD_BUFFER = 8 * 1024
    }
}
