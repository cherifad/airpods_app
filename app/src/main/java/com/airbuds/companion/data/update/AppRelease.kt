package com.airbuds.companion.data.update

import org.json.JSONException
import org.json.JSONObject

/** One downloadable APK attached to a GitHub release. */
data class AppAsset(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String?,
)

/** Latest release that carries an installable APK. */
data class AppRelease(
    val tag: String,
    val version: AppVersion,
    val asset: AppAsset,
)

/**
 * Parses the GitHub `releases/latest` response.
 *
 * Only `.apk` assets are considered, preferring names containing "release"
 * over debug/test artifacts.
 */
object AppReleaseParser {

    fun parse(json: String): AppRelease? {
        val root = try {
            JSONObject(json)
        } catch (_: JSONException) {
            return null
        }

        val tag = root.optString("tag_name")
        val version = AppVersion.parse(tag) ?: return null
        val assets = root.optJSONArray("assets") ?: return null

        val candidates = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                if (!name.endsWith(".apk", ignoreCase = true) || url.isEmpty()) continue
                add(
                    AppAsset(
                        name = name,
                        url = url,
                        size = asset.optLong("size"),
                        sha256 = asset.optString("digest")
                            .removePrefix("sha256:")
                            .takeIf { it.isNotEmpty() },
                    ),
                )
            }
        }

        val asset = candidates.firstOrNull { it.name.contains("release", ignoreCase = true) }
            ?: candidates.firstOrNull()
            ?: return null

        return AppRelease(tag = tag, version = version, asset = asset)
    }
}
