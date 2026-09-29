package com.xudong.wubitrainer.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** What the engine is allowed to do with progress, so tests can substitute a fake. */
interface ProgressAccess {
    fun get(char: String): CharProgress
    fun put(progress: CharProgress)
    fun snapshot(): List<CharProgress>
}

enum class LoadSource { NONE, MAIN, BACKUP, EMPTY_AFTER_FAILURE }

data class LoadReport(
    val source: LoadSource,
    val rows: Int,
    val warnings: List<String>,
    val quarantinePath: String?,
)

/**
 * The durable progress record: a single human-readable TSV file (PRD §3.2/§3.3).
 *
 * Durability strategy — a learner must never lose work to a crash:
 *  1. writes go to `progress.tsv.tmp`, are `fsync`ed, and are then **atomically renamed**
 *     over `progress.tsv`, so a reader never observes a half-written file;
 *  2. the previous good file is copied to `progress.tsv.bak` before the rename;
 *  3. on load, `progress.tsv` is tried first, then `.bak`; if both are unusable the bad file
 *     is **quarantined** (`progress.tsv.corrupt-<ts>`) rather than deleted, and the app
 *     starts empty with a visible warning.
 *
 * The store is deliberately dumb: it owns the map, answers reads, records mutations, and
 * writes on demand. Deciding *when* to write (debounce) is the controller's job.
 */
class ProgressStore(private val dir: File) : ProgressAccess {

    private val lock = Any()
    private val rows = LinkedHashMap<String, CharProgress>()
    private val extras = HashMap<String, String>()
    private var warnings: List<String> = emptyList()

    val file: File get() = File(dir, ProgressTsv.FILE_NAME)
    private val backup: File get() = File(dir, ProgressTsv.BACKUP_NAME)
    private val temp: File get() = File(dir, ProgressTsv.TEMP_NAME)

    /** Path shown in 设置 so the learner can pull the file off the device. */
    val filePath: String get() = file.absolutePath

    override fun get(char: String): CharProgress =
        synchronized(lock) { rows[char] ?: CharProgress(char) }

    override fun put(progress: CharProgress) {
        synchronized(lock) { rows[progress.char] = progress }
    }

    override fun snapshot(): List<CharProgress> = synchronized(lock) { rows.values.toList() }

    fun remove(char: String) {
        synchronized(lock) {
            rows.remove(char)
            extras.remove(char)
        }
    }

    fun replaceAll(newRows: Collection<CharProgress>) {
        synchronized(lock) {
            rows.clear()
            // Deliberately keep `extras`: this is used by the reset buttons, and the
            // forward-compatibility columns belong to characters that survive the reset.
            for (p in newRows) rows[p.char] = p
        }
    }

    fun stats(): ProgressStats = synchronized(lock) {
        var tc = 0
        var tw = 0
        var passed = 0
        var mist = 0
        var practised = 0
        for (p in rows.values) {
            tc += p.correct
            tw += p.wrong
            if (p.passed) passed++
            if (p.inMistake) mist++
            if (p.wrong > 0 || p.correct > 0) practised++
        }
        ProgressStats(practised, tc, tw, passed, mist)
    }

    fun load(): LoadReport = synchronized(lock) {
        rows.clear()
        extras.clear()
        warnings = emptyList()

        val fromMain = readInto(file)
        if (fromMain != null) {
            return LoadReport(LoadSource.MAIN, rows.size, fromMain, null)
        }
        if (file.exists()) {
            // The main file is present but unreadable: preserve it for forensics.
            val quarantine = quarantine(file)
            val fromBackup = readInto(backup)
            if (fromBackup != null) {
                return LoadReport(
                    LoadSource.BACKUP, rows.size,
                    listOf("progress.tsv was unreadable; recovered from backup") + fromBackup,
                    quarantine,
                )
            }
            rows.clear()
            extras.clear()
            return LoadReport(
                LoadSource.EMPTY_AFTER_FAILURE, 0,
                listOf("progress.tsv and its backup were unreadable; starting empty"),
                quarantine,
            )
        }
        val fromBackup = readInto(backup)
        if (fromBackup != null) {
            return LoadReport(LoadSource.BACKUP, rows.size, fromBackup, null)
        }
        return LoadReport(LoadSource.NONE, 0, emptyList(), null)
    }

    /**
     * Read [source] into the map. Returns null when the file does not exist or cannot be
     * parsed at all; individual bad rows are tolerated (see [ProgressTsv.decode]).
     */
    private fun readInto(source: File): List<String>? {
        if (!source.isFile || source.length() == 0L) return null
        val text = try {
            source.readText(Charsets.UTF_8)
        } catch (t: Throwable) {
            return null
        }
        val decoded = ProgressTsv.decode(text)
        // A file whose every data line failed is treated as corrupt, not as "empty".
        val lookedLikeData = text.lineSequence().any { it.isNotBlank() && !it.startsWith("#") }
        if (lookedLikeData && decoded.rows.isEmpty()) return null
        rows.putAll(decoded.rows)
        extras.putAll(decoded.extras)
        warnings = decoded.warnings
        return decoded.warnings
    }

    private fun quarantine(source: File): String? = try {
        val dest = File(dir, "progress.tsv.corrupt-${System.currentTimeMillis()}")
        Files.move(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        dest.absolutePath
    } catch (t: Throwable) {
        null
    }

    /** Serialise and durably replace the file (temp → fsync → backup → atomic rename). */
    fun writeNow(): Boolean = synchronized(lock) {
        val text = try {
            ProgressTsv.encode(rows.values, extras, System.currentTimeMillis())
        } catch (t: Throwable) {
            return false
        }
        try {
            dir.mkdirs()
            FileOutputStream(temp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (file.isFile) {
                runCatching {
                    Files.copy(
                        file.toPath(), backup.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }
            Files.move(
                temp.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
            true
        } catch (t: Throwable) {
            // Last resort: a non-atomic move beats losing the answer entirely.
            runCatching {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.isSuccess
        }
    }

    fun currentWarnings(): List<String> = synchronized(lock) { warnings.toList() }
}
