package ru.spgsroot.peachy.documents.office.engine

import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.FileVisitOption
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet

/**
 * Materialises only the vetted `assets/unpack` tree into an app-owned directory before native init,
 * mirroring `android/source/src/java/org/libreoffice/LibreOfficeMainActivity.java:314`
 * (`copyFromAssets(getAssets(), "unpack", dataDir)`).
 *
 * Why the copy exists upstream: `sal/android/lo-bootstrap.c:230-245` only *indexes* APK assets for
 * the `/assets/` virtual file system; the files listed in `copyUnpackAssets`
 * (`program/<name>.rdb`, `program/sofficerc`, `user/fonts/<name>.ttf`, `etc/fonts/fonts.conf`) are read by
 * fontconfig/UNO through plain POSIX paths below `$APP_DATA_DIR`
 * (`build.gradle.kts:296-333`). `initializeNative` derives `FONTCONFIG_FILE` and the
 * `libreofficekit_hook` path from the `dataDir` argument it is given
 * (`sal/android/libreofficekit-jni.c:110-181`), so passing this owned directory keeps the user
 * profile (`UserInstallation=$APP_DATA_DIR`), the type registries and the font cache inside our own
 * tree instead of the shared app data directory.
 *
 * Properties: idempotent (completion marker), versioned (pinned commit + APK size/mtime + asset
 * path list), bounded (file/byte/depth caps), prefix stripped, and deletions restricted to
 * directories matching [isOwnedUnpackName]. Nothing here logs a path or asset content.
 */
internal class OfficeUnpack(private val context: Context) {

    /** Returns the extracted directory, or a typed `ENGINE_ASSETS` refusal. */
    fun ensureExtracted(commit: String): Result<File> = synchronized(EXTRACTION_LOCK) {
        ensureExtractedLocked(commit)
    }

    private fun ensureExtractedLocked(commit: String): Result<File> {
        val root = try {
            File(context.filesDir, OfficeUnpackLayout.OWNED_ROOT).canonicalFile
        } catch (failed: IOException) {
            return failure()
        }
        if (!root.isDirectory && !root.mkdirs()) return failure()

        val apk = try {
            File(context.applicationInfo.sourceDir)
        } catch (missing: Exception) {
            return failure()
        }
        if (!apk.isFile) return failure()

        val relativePaths = ArrayList<String>(64)
        if (!collect(context.assets, OfficeUnpackLayout.ASSET_ROOT, 0, relativePaths)) return failure()
        if (relativePaths.isEmpty()) return failure()
        if (relativePaths.toHashSet().size != relativePaths.size) return failure()

        val fingerprint = unpackFingerprint(relativePaths, apk.length(), apk.lastModified())
        val targetName = unpackDirectoryName(commit, fingerprint)
        if (!isOwnedUnpackName(targetName)) return failure()
        val target = File(root, targetName)
        val expected = OfficeUnpackMarker(
            format = OfficeUnpackLayout.MARKER_FORMAT,
            commit = commit.lowercase(),
            fingerprint = fingerprint,
            files = relativePaths.size,
        )

        if (markerMatches(readMarker(target), expected) && containsAll(target, relativePaths)) {
            return ensureCacheDirectory(target)?.let { Result.success(it) } ?: failure()
        }

        val staging = File(root, "$targetName.tmp")
        if (!isOwnedUnpackStagingName(staging.name)) return failure()
        if (staging.parentFile?.canonicalPath != root.canonicalPath) return failure()
        if (!safeDelete(staging)) return failure()
        if (!staging.mkdirs()) return failure()

        var bytes = 0L
        var filesCopied = 0
        try {
            val stagingCanonical = staging.canonicalPath
            for (relative in relativePaths.sorted()) {
                val destination = File(staging, relative)
                val canonical = destination.canonicalFile
                if (!canonical.path.startsWith(stagingCanonical + File.separator)) {
                    throw IOException("unpack path escaped")
                }
                val parent = canonical.parentFile ?: throw IOException("unpack path has no parent")
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("unpack mkdir failed")

                var fileBytes = 0L
                context.assets.open("${OfficeUnpackLayout.ASSET_ROOT}/$relative").use { input ->
                    FileOutputStream(canonical).use { output ->
                        val buffer = ByteArray(COPY_BUFFER)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            fileBytes += read
                            if (fileBytes > OfficeUnpackLimits.MAX_FILE_BYTES ||
                                bytes > OfficeUnpackLimits.MAX_TOTAL_BYTES - read
                            ) {
                                throw IOException("unpack budget exceeded")
                            }
                            output.write(buffer, 0, read)
                        }
                        output.fd.sync()
                    }
                }
                if (!OfficeUnpackBudget.allowsFile(filesCopied, bytes, fileBytes)) {
                    throw IOException("unpack budget exceeded")
                }
                bytes += fileBytes
                filesCopied++
            }

            writeMarker(staging, expected)
            if (target.exists() && !safeDelete(target)) throw IOException("stale unpack dir")
            if (!staging.renameTo(target)) throw IOException("unpack rename failed")
        } catch (failed: IOException) {
            safeDelete(staging)
            return failure()
        } catch (denied: SecurityException) {
            safeDelete(staging)
            return failure()
        }

        pruneOtherVersions(root, target)
        return ensureCacheDirectory(target)?.let { Result.success(it) } ?: failure()
    }

    /**
     * A marker only proves an extraction once finished; the tree can still be partially removed
     * afterwards. Every path enumerated from the APK must exist as a regular file, without
     * following symlinks, or the directory is rebuilt.
     */
    private fun containsAll(target: File, relativePaths: List<String>): Boolean {
        val targetCanonical = try {
            target.canonicalPath
        } catch (failed: IOException) {
            return false
        }
        for (relative in relativePaths) {
            val file = try {
                File(target, relative).canonicalFile
            } catch (failed: IOException) {
                return false
            }
            if (!file.path.startsWith(targetCanonical + File.separator)) return false
            val regular = try {
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
            } catch (denied: SecurityException) {
                false
            } catch (failed: Exception) {
                false
            }
            if (!regular) return false
        }
        return true
    }

    private fun ensureCacheDirectory(target: File): File? = try {
        val cache = File(target, OfficeUnpackLayout.CACHE_DIRECTORY)
        if (cache.isDirectory || cache.mkdirs()) target.canonicalFile else null
    } catch (failed: IOException) {
        null
    }

    private fun collect(
        assets: AssetManager,
        directory: String,
        depth: Int,
        out: MutableList<String>,
    ): Boolean {
        if (depth > OfficeUnpackLimits.MAX_DEPTH) return false
        val names: Array<String>? = try {
            assets.list(directory)
        } catch (failed: IOException) {
            return false
        }
        if (names == null) return false
        for (name in names) {
            if (!isSafeAssetName(name)) return false
            val child = "$directory/$name"
            val children: Array<String>? = try {
                assets.list(child)
            } catch (failed: IOException) {
                return false
            }
            if (children == null) return false
            if (children.isEmpty()) {
                val relative = child.removePrefix("${OfficeUnpackLayout.ASSET_ROOT}/")
                val safe = safeRelativePath(relative) ?: return false
                if (out.size >= OfficeUnpackLimits.MAX_FILES) return false
                out.add(safe)
            } else if (!collect(assets, child, depth + 1, out)) {
                return false
            }
        }
        return true
    }

    private fun readMarker(target: File): OfficeUnpackMarker? {
        val marker = File(target, OfficeUnpackLayout.MARKER_NAME)
        if (!marker.isFile || marker.length() > OfficeUnpackLimits.MAX_MARKER_BYTES) return null
        return try {
            val bytes = ByteArray(marker.length().toInt())
            FileInputStream(marker).use { input ->
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    if (read < 0) break
                    offset += read
                }
                if (offset != bytes.size) return null
            }
            OfficeUnpackMarker.parse(String(bytes, Charsets.UTF_8))
        } catch (failed: IOException) {
            null
        } catch (denied: SecurityException) {
            null
        }
    }

    private fun writeMarker(directory: File, marker: OfficeUnpackMarker) {
        FileOutputStream(File(directory, OfficeUnpackLayout.MARKER_NAME)).use { output ->
            output.write(marker.render().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
    }

    /** Best-effort removal of our own older `unpack-*` and crash-leftover `*.tmp` directories. */
    private fun pruneOtherVersions(root: File, current: File) {
        val siblings = root.listFiles() ?: return
        for (sibling in siblings) {
            if (sibling.name == current.name) continue
            if (!isOwnedUnpackName(sibling.name) && !isOwnedUnpackStagingName(sibling.name)) continue
            if (sibling.parentFile?.canonicalPath != root.canonicalPath) continue
            safeDelete(sibling)
        }
    }

    /** Deletes a directory tree without following symbolic links; false on any failure. */
    private fun safeDelete(path: File): Boolean {
        if (!path.exists()) return true
        return try {
            Files.walkFileTree(
                path.toPath(),
                EnumSet.noneOf(FileVisitOption::class.java),
                Int.MAX_VALUE,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.delete(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(dir: Path, failure: IOException?): FileVisitResult {
                        if (failure != null) throw failure
                        Files.delete(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            true
        } catch (failed: IOException) {
            false
        } catch (denied: SecurityException) {
            false
        }
    }

    private fun failure(): Result<File> =
        Result.failure(OfficeFailure.Unavailable(OfficeUnavailableReason.ENGINE_ASSETS))

    private companion object {
        const val COPY_BUFFER = 64 * 1024

        /** One engine per process is the norm, but two routes may race the same staging path. */
        val EXTRACTION_LOCK = Any()
    }
}
