package com.cinerating.update

import com.squareup.moshi.Json

/**
 * Mirrors version.json hosted on GitHub Pages:
 * https://reboot2004.github.io/CineRating/version.json
 * Produced by .github/workflows/release.yml on every tag.
 */
data class UpdateInfo(
    @Json(name = "versionCode") val versionCode: Int = 0,
    @Json(name = "versionName") val versionName: String = "",
    @Json(name = "apkUrl") val apkUrl: String = "",
    @Json(name = "changelog") val changelog: String = "",
    @Json(name = "mandatory") val mandatory: Boolean = false,
    @Json(name = "sha256") val sha256: String = ""
)
