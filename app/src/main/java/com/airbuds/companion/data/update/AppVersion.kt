package com.airbuds.companion.data.update

/**
 * Numeric application version.
 *
 * Tags are accepted with an optional `v` prefix and an optional suffix
 * (`v0.2.0`, `0.2.0-beta` → `0.2.0`). Missing components default to 0.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        fun parse(raw: String?): AppVersion? {
            val cleaned = raw
                ?.trim()
                ?.removePrefix("v")
                ?.substringBefore('-')
                ?.substringBefore('+')
                ?.takeIf { it.isNotEmpty() }
                ?: return null

            val parts = cleaned.split('.')
            if (parts.size < 2 || parts.any { it.isEmpty() }) return null
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            return AppVersion(
                major = numbers[0],
                minor = numbers.getOrElse(1) { 0 },
                patch = numbers.getOrElse(2) { 0 },
            )
        }
    }
}
