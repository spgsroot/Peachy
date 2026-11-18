package ru.spgsroot.peachy.documents.office.engine

import android.content.Context
import java.io.IOException

/**
 * Fail-closed capability probe. It never initialises the native library: classes are resolved with
 * `initialize = false` and the asset tree is opened through `AssetManager` only. Every check is an
 * ordered refusal reason, so the UI can say *why* there is no editor instead of pretending one
 * exists.
 *
 * Requirements are documented in `.impeccable/office-bridge-report.md`: the JNI Java glue classes,
 * the unpacked engine tree (`program/`, `unpack/`), and the pinned build manifest shipped as
 * `assets/office-engine/build-manifest.properties`.
 */
internal class OfficeEngineGate(private val context: Context) {

    fun probe(): OfficeAvailability {
        val manifest = readManifest()
            ?: return OfficeAvailability.Unavailable(OfficeUnavailableReason.BUILD_MANIFEST)
        manifest.unavailableReason()?.let { return OfficeAvailability.Unavailable(it) }
        if (!hasEngineAssets()) return OfficeAvailability.Unavailable(OfficeUnavailableReason.ENGINE_ASSETS)
        if (!LokBindings.isBridgePresent(context.classLoader)) {
            return OfficeAvailability.Unavailable(OfficeUnavailableReason.BRIDGE_CLASSES)
        }
        return OfficeAvailability.Available(manifest)
    }

    private fun readManifest(): OfficeBuildManifest? = try {
        context.assets.open(OfficeBuildManifest.ASSET_PATH).use { stream ->
            val buffer = ByteArray(MAX_MANIFEST_BYTES)
            var total = 0
            while (total < buffer.size) {
                val read = stream.read(buffer, total, buffer.size - total)
                if (read < 0) break
                total += read
            }
            OfficeBuildManifest.parse(String(buffer, 0, total, Charsets.UTF_8))
        }
    } catch (missing: IOException) {
        null
    } catch (invalid: IllegalArgumentException) {
        null
    } catch (invalid: IllegalStateException) {
        null
    } catch (denied: SecurityException) {
        null
    }

    private fun hasEngineAssets(): Boolean =
        assetPresent("program/fundamentalrc") &&
            assetPresent("unpack/program/offapi.rdb") &&
            assetPresent("unpack/etc/fonts/fonts.conf")

    private fun assetPresent(path: String): Boolean = try {
        context.assets.open(path).close()
        true
    } catch (missing: IOException) {
        false
    }

    private companion object {
        const val MAX_MANIFEST_BYTES = 4096
    }
}
