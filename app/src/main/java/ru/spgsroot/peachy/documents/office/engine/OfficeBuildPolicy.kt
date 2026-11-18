package ru.spgsroot.peachy.documents.office.engine

/**
 * The two environment variables that must be in the engine process before LOK initialises.
 *
 * `desktop/source/lib/init.cxx` reads them in `lo_initialize()` (the office constructor) and feeds
 * `HostFilter` (`tools/source/inet/hostfilter.cxx`):
 * - `LOK_HOST_ALLOWLIST=^$` rejects nonempty hosts where the LOK host filter is consulted.
 * - `LOK_ALLOWED_EXTREF_PATHS=` configures an empty allowlist for `file:` URLs checked
 *   by the external-reference filter.
 *
 * The bridge does not enable `LOK_HOST_ALLOWLIST_EXEMPT_VERIFY_HOST`, which relaxes TLS
 * verification for allowlisted hosts. These settings describe LOK's filters, not a process sandbox.
 */
object OfficeEnvironmentPolicy {

    const val HOST_ALLOWLIST = "LOK_HOST_ALLOWLIST"
    const val HOST_ALLOWLIST_DENY_ALL = "^$"
    const val ALLOWED_EXTREF_PATHS = "LOK_ALLOWED_EXTREF_PATHS"

    val FORBIDDEN_KEYS: Set<String> = setOf("LOK_HOST_ALLOWLIST_EXEMPT_VERIFY_HOST")

    /** Exact strings handed to `LibreOfficeKit.putenv`, in order, before native init. */
    val ASSIGNMENTS: List<String> = listOf(
        "$HOST_ALLOWLIST=$HOST_ALLOWLIST_DENY_ALL",
        "$ALLOWED_EXTREF_PATHS=",
    )
}

enum class OfficeManifestProblem {
    COMMIT_MISMATCH,
    MACROS_NOT_LOCKED,
    TILE_MODE_UNSUPPORTED,
}

/**
 * The packaging declaration the APK must ship at `assets/office-engine/build-manifest.properties`.
 * It is the only app-side evidence about *which* LibreOffice build the native libraries came from:
 * `LibreOfficeKit` exposes no version or compile-flag query in the pinned Java glue, so this file is
 * a build-time declaration, not a cryptographic attestation. A missing or contradictory manifest
 * makes the engine unavailable instead of guessing.
 *
 * The manifest must declare `tile_mode=rgba`. The pinned Android build forces cairo's RGBA pixel
 * order (`configure.ac`: `enable_cairo_rgba=yes` for Android), so `doc_getTileMode()` reports
 * `LOK_TILEMODE_RGBA` and upstream copies that buffer straight into `Bitmap.Config.ARGB_8888`,
 * whose bytes on Android are also `R,G,B,A`. Any other declared mode (for example `bgra` from a
 * build without `ENABLE_CAIRO_RGBA`) would need a channel swap this bridge has not verified, so the
 * declaration is rejected and the engine is not offered. See [OfficeTilePixels].
 */
data class OfficeBuildManifest(
    val commit: String,
    val tag: String,
    val haveFeatureAndroidLok: Boolean,
    val tileMode: String,
    val abi: String?,
) {

    fun problems(): List<OfficeManifestProblem> {
        val problems = ArrayList<OfficeManifestProblem>(3)
        if (!commit.equals(PINNED_COMMIT, ignoreCase = true)) problems.add(OfficeManifestProblem.COMMIT_MISMATCH)
        if (haveFeatureAndroidLok) problems.add(OfficeManifestProblem.MACROS_NOT_LOCKED)
        if (OfficeTilePixels.copyStrategy(tileMode) == null) {
            problems.add(OfficeManifestProblem.TILE_MODE_UNSUPPORTED)
        }
        return problems
    }

    fun unavailableReason(): OfficeUnavailableReason? {
        val problems = problems()
        return when {
            OfficeManifestProblem.COMMIT_MISMATCH in problems -> OfficeUnavailableReason.BUILD_MANIFEST
            OfficeManifestProblem.MACROS_NOT_LOCKED in problems -> OfficeUnavailableReason.MACRO_LOCK
            OfficeManifestProblem.TILE_MODE_UNSUPPORTED in problems -> OfficeUnavailableReason.TILE_MODE
            else -> null
        }
    }

    companion object {
        const val ASSET_PATH = "office-engine/build-manifest.properties"

        /** Annotated tag `libreoffice-26.8.3.1` checked out at this commit. */
        const val PINNED_COMMIT = "861effc5289d539f15c4c18d1ba8579ffd78728b"
        const val PINNED_TAG = "libreoffice-26.8.3.1"
        /** The pinned Android build's `doc_getTileMode()`; must match [OfficeTilePixels]. */
        const val SUPPORTED_TILE_MODE = "rgba"

        private const val KEY_COMMIT = "commit"
        private const val KEY_TAG = "tag"
        private const val KEY_MACROS = "have_feature_android_lok"
        private const val KEY_TILE_MODE = "tile_mode"
        private const val KEY_ABI = "abi"

        /** Strict parser: a missing or repeated required key throws, callers fail closed. */
        fun parse(text: String): OfficeBuildManifest {
            val values = HashMap<String, String>(8)
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith('#') || trimmed.startsWith('!')) continue
                val separator = trimmed.indexOf('=')
                require(separator > 0) { "malformed manifest line" }
                val key = trimmed.substring(0, separator).trim()
                val value = trimmed.substring(separator + 1).trim()
                require(key.isNotEmpty()) { "empty manifest key" }
                require(values.put(key, value) == null) { "duplicate manifest key" }
            }
            val commit = values[KEY_COMMIT] ?: error("missing commit")
            val tag = values[KEY_TAG] ?: error("missing tag")
            val macros = values[KEY_MACROS] ?: error("missing have_feature_android_lok")
            return OfficeBuildManifest(
                commit = commit,
                tag = tag,
                haveFeatureAndroidLok = when (macros.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> error("have_feature_android_lok is not a boolean")
                },
                tileMode = values[KEY_TILE_MODE] ?: error("missing tile_mode"),
                abi = values[KEY_ABI],
            )
        }
    }
}
