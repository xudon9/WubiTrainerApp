package com.xudong.wubitrainer

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.xudong.wubitrainer.data.AcceptMode
import com.xudong.wubitrainer.data.AcceptScope
import com.xudong.wubitrainer.data.CharEntry
import com.xudong.wubitrainer.data.CharProgress
import com.xudong.wubitrainer.data.LoadSource
import com.xudong.wubitrainer.data.ProgressStats
import com.xudong.wubitrainer.data.ProgressStore
import com.xudong.wubitrainer.data.Screen
import com.xudong.wubitrainer.data.SessionMode
import com.xudong.wubitrainer.data.SessionStats
import com.xudong.wubitrainer.data.Settings
import com.xudong.wubitrainer.data.SettingsStore
import com.xudong.wubitrainer.data.WubiRepository
import com.xudong.wubitrainer.engine.AddResult
import com.xudong.wubitrainer.engine.EngineConfig
import com.xudong.wubitrainer.engine.PracticeEngine
import com.xudong.wubitrainer.engine.PrefixState
import com.xudong.wubitrainer.engine.Verdict
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

/** A transient line of feedback. [id] lets the UI dismiss exactly the one it showed. */
data class Notice(val id: Long, val text: String, val tone: Tone = Tone.INFO) {
    enum class Tone { INFO, GOOD, BAD }
}

/**
 * The single state holder behind the whole UI.
 *
 * It owns the repository, the progress file, the settings and the engine, and it exposes
 * everything the Compose screens read as observable state. Keeping it a plain class (rather
 * than an AndroidX ViewModel) is deliberate: the activity declares the relevant
 * `configChanges`, so rotation does not recreate anything and there is no process-death
 * restoration story to get subtly wrong for a file-backed store.
 */
class AppController(context: Context) {

    private val appContext: Context = context.applicationContext
    private val settingsStore = SettingsStore(appContext)
    private val progress = ProgressStore(appContext.filesDir)
    private val repository = WubiRepository(appContext.assets)
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Main.immediate)

    private var engine: PracticeEngine? = null

    private var flushJob: Job? = null
    private var dirty = false
    private var noticeSeq = 0L
    private var fullTableJob: Job? = null

    // ------------------------------------------------------------------ observable state

    var screen by mutableStateOf(Screen.PRACTICE); private set
    var loading by mutableStateOf(true); private set
    var loadingMessage by mutableStateOf("正在载入字表…"); private set

    var settings by mutableStateOf(Settings()); private set
    var current by mutableStateOf<CharEntry?>(null); private set
    var buffer by mutableStateOf(""); private set
    var prefixState by mutableStateOf(PrefixState.EMPTY); private set
    var revealed by mutableStateOf(false); private set
    var pendingWrong by mutableStateOf<Verdict.Wrong?>(null); private set
    /**
     * Transient answer feedback, shown in ONE place: a pop under the stats bar (FR-35).
     *
     * "✓ 正确" and "已通过" used to be announced in different spots — the first flashed inside the
     * tally line down by the keypad, the second went to the bottom Snackbar — so the same kind of
     * news appeared in two unrelated places. They share this channel now; the UI owns the timing.
     */
    data class AnswerFlash(val seq: Long, val text: String, val good: Boolean)

    var answerFlash by mutableStateOf<AnswerFlash?>(null); private set
    private var flashSeq = 0L
    var mode by mutableStateOf(SessionMode.REGULAR); private set
    var session by mutableStateOf(SessionStats()); private set

    var stats by mutableStateOf(ProgressStats()); private set
    var mistakes by mutableStateOf<List<CharProgress>>(emptyList()); private set
    var fullTableReady by mutableStateOf(false); private set
    var poolSize by mutableStateOf(0); private set
    var eligibleRemaining by mutableStateOf(0); private set

    var notice by mutableStateOf<Notice?>(null); private set
    var loadWarnings by mutableStateOf<List<String>>(emptyList()); private set
    var quarantinePath by mutableStateOf<String?>(null); private set

    val progressFilePath: String get() = progress.filePath

    /** Size of the full 70,944-character Wubi86 table, once it has finished loading. */
    val fullTableSize: Int get() = repository.fullTableSize

    /** The set of codes currently accepted for the character on screen. */
    val allowedCodesForCurrent: List<String>
        get() = current?.allowedCodes(settings.acceptScope) ?: emptyList()

    // ------------------------------------------------------------------------- lifecycle

    fun start() {
        if (engine != null) return
        settings = settingsStore.load()

        scope.launch {
            val report = withContext(Dispatchers.IO) { progress.load() }
            quarantinePath = report.quarantinePath
            val notes = ArrayList<String>()
            if (report.source == LoadSource.BACKUP) {
                notes += "主进度文件损坏，已从备份恢复（共 ${report.rows} 条）。"
            }
            if (report.source == LoadSource.EMPTY_AFTER_FAILURE) {
                notes += "进度文件与其备份都无法读取，已从空白开始；原文件已保留。"
            }
            notes += report.warnings.take(3)
            loadWarnings = notes

            loadingMessage = "正在载入字表…"
            repository.loadPool()
            poolSize = repository.poolSize

            engine = PracticeEngine(
                poolModern = repository.poolModern,
                poolOverall = repository.poolOverall,
                lookup = { repository.lookup(it) },
                progress = progress,
                config = { EngineConfig.of(settings) },
            )

            loading = false
            refreshDerived()
            nextCharacter()
            if (notes.isNotEmpty()) pushNotice(notes.first(), Notice.Tone.BAD)

            // The full table is only needed for hand-added characters; load it off the
            // critical path so the first drill starts immediately.
            fullTableJob = scope.launch {
                repository.loadFullTable()
                fullTableReady = true
            }
        }
    }

    fun flushNow() {
        flushJob?.cancel()
        if (!dirty) return
        runCatching { progress.writeNow() }
        dirty = false
    }

    fun dispose() {
        flushNow()
        job.cancel()
    }

    private fun markDirty(immediate: Boolean = false) {
        dirty = true
        flushJob?.cancel()
        flushJob = scope.launch {
            if (!immediate) delay(FLUSH_DEBOUNCE_MS)
            withContext(Dispatchers.IO) { progress.writeNow() }
            dirty = false
        }
    }

    private fun refreshDerived() {
        val e = engine ?: return
        stats = e.stats()
        mistakes = e.mistakeRows()
    }

    private fun pushNotice(text: String, tone: Notice.Tone = Notice.Tone.INFO) {
        notice = Notice(++noticeSeq, text, tone)
    }

    fun dismissNotice(id: Long) {
        if (notice?.id == id) notice = null
    }

    // ------------------------------------------------------------------------- navigation

    fun openScreen(target: Screen) {
        screen = target
        if (target == Screen.MISTAKES || target == Screen.SETTINGS) refreshDerived()
    }

    // ---------------------------------------------------------------------- key handling

    /**
     * Handle one typed letter. Returns true when it was consumed as input, false when the
     * caller should treat it as a shortcut candidate. The 'z' conflict rule (FR-27) lives
     * here rather than in the UI so it is testable and applies to the on-screen keypad and a
     * hardware keyboard alike.
     */
    fun typeLetter(letter: Char): Boolean {
        if (pendingWrong != null) return false
        val entry = current ?: return false
        val c = letter.lowercaseChar()
        if (c !in 'a'..'z') return false
        if (buffer.length >= MAX_BUFFER) return false
        buffer += c
        val state = engine?.classify(buffer, entry) ?: PrefixState.EMPTY
        prefixState = state

        val auto = when {
            settings.acceptMode == AcceptMode.ON_THE_FLY && state == PrefixState.ACCEPTABLE -> true
            settings.failOnDeadPrefix && state == PrefixState.DEAD -> true
            else -> false
        }
        if (auto) commit()
        return true
    }

    /** True when 'z' should reveal rather than be typed (FR-27). */
    fun zIsShortcut(): Boolean {
        val entry = current ?: return false
        return settings.revealShortcut && !(engine?.usesLetterZ(entry) ?: false)
    }

    fun backspace() {
        if (pendingWrong != null) return
        if (buffer.isEmpty()) return
        buffer = buffer.dropLast(1)
        prefixState = current?.let { engine?.classify(buffer, it) } ?: PrefixState.EMPTY
    }

    fun clearBuffer() {
        if (pendingWrong != null) return
        buffer = ""
        prefixState = PrefixState.EMPTY
    }

    /**
     * Enter. Advances past a correction card; otherwise commits whatever is in the buffer.
     * An empty buffer is never an answer, so Enter on it is simply ignored.
     */
    fun pressEnter() {
        if (pendingWrong != null) {
            acknowledgeWrong()
            return
        }
        if (buffer.isEmpty()) return
        commit()
    }

    fun toggleReveal() {
        if (pendingWrong != null) return
        if (current == null) return
        revealed = !revealed
    }

    /**
     * Move on without answering.
     *
     * Deliberately touches **no** counter: a skip is not a mistake, so it must not reset `correct`
     * and must not put the character in the 错题集 — that is what the wrong-answer path is for.
     * The only guard the engine needs is its own "never the same character twice in a row".
     */
    fun skipCurrent() {
        if (current == null) return
        nextCharacter()
    }

    /**
     * Put the character on screen into the 错题集 by hand.
     *
     * Does **not** advance: the learner has just said this character is a problem, so the useful
     * next move is theirs — keep trying it, or press 跳过. Advancing here would be the
     * unrecoverable choice, since there is no way to ask for the same character back.
     */
    fun addCurrentToMistakeSet() {
        val entry = current ?: return
        when (val result = engine?.addToMistakeSet(entry.char)) {
            is AddResult.Added -> {
                markDirty(immediate = true)
                refreshDerived()
                pushNotice(
                    if (result.alreadyInSet) {
                        "${entry.char} 已经在错题集里了"
                    } else {
                        "${entry.char} 已加入错题集"
                    },
                    Notice.Tone.GOOD,
                )
            }

            is AddResult.Rejected -> pushNotice("${entry.char}：${result.reason}", Notice.Tone.BAD)

            null -> Unit
        }
    }

    fun openExplanation() {
        if (!settings.explainShortcut) return
        val char = pendingWrong?.entry?.char ?: current?.char ?: return
        openUrl(explanationUrl(char))
    }

    fun explanationUrl(char: String): String =
        EXPLAIN_BASE + URLEncoder.encode(char, "UTF-8")

    private fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            appContext.startActivity(intent)
        } catch (t: Throwable) {
            pushNotice("没有可以打开链接的应用：$url", Notice.Tone.BAD)
        }
    }

    // ------------------------------------------------------------------------ drill flow

    private fun commit() {
        val entry = current ?: return
        val e = engine ?: return
        val verdict = e.submit(buffer, entry, mode)
        buffer = ""
        prefixState = PrefixState.EMPTY
        session = session.copy(answered = session.answered + 1)

        when (verdict) {
            is Verdict.Correct -> {
                session = session.copy(
                    correct = session.correct + 1,
                    passedThisSession = session.passedThisSession + if (verdict.passedNow) 1 else 0,
                    leftMistakeThisSession = session.leftMistakeThisSession + if (verdict.leftMistakeSet) 1 else 0,
                )
                // One line, one place. Passing outranks a plain "correct", and when a character
                // both passes and leaves the mistake set the pop says both rather than dropping one.
                answerFlash = AnswerFlash(
                    seq = ++flashSeq,
                    text = when {
                        verdict.passedNow && verdict.leftMistakeSet -> "${entry.char} 已通过 ✓　已移出错题集"
                        verdict.passedNow -> "${entry.char} 已通过 ✓"
                        verdict.leftMistakeSet -> "${entry.char} 已移出错题集 ✓"
                        else -> "✓ 正确"
                    },
                    good = true,
                )
                pendingWrong = null
                // State transitions are never left to the debounce.
                markDirty(immediate = verdict.passedNow || verdict.leftMistakeSet)
                refreshDerived()
                nextCharacter()
            }

            is Verdict.Wrong -> {
                // Errors are announced in the same place as correct answers (FR-35). The correction
                // card still carries the detail; this is the at-a-glance signal.
                answerFlash = AnswerFlash(seq = ++flashSeq, text = "✗ 错误", good = false)
                pendingWrong = verdict
                markDirty(immediate = true)
                refreshDerived()
            }
        }
    }

    fun acknowledgeWrong() {
        pendingWrong = null
        nextCharacter()
    }

    private fun nextCharacter() {
        val e = engine ?: return
        // The engine itself refuses to repeat the previous character (FR-24).
        val picked = e.sample(mode)
        current = picked
        buffer = ""
        prefixState = PrefixState.EMPTY
        revealed = false
        eligibleRemaining = e.eligibleCount(mode)
    }

    /** Start a fresh session in [target] mode, resetting the per-session tallies. */
    fun switchMode(target: SessionMode) {
        if (mode == target && current != null) return
        mode = target
        session = SessionStats()
        engine?.forgetLastSampled()
        pendingWrong = null
        refreshDerived()
        nextCharacter()
    }

    fun restartSession() {
        session = SessionStats()
        engine?.forgetLastSampled()
        pendingWrong = null
        refreshDerived()
        nextCharacter()
    }

    // --------------------------------------------------------------------------- settings

    fun updateSettings(next: Settings) {
        val structural = next.passCount != settings.passCount ||
            next.freqSource != settings.freqSource ||
            next.poolSize != settings.poolSize ||
            next.weightAlpha != settings.weightAlpha ||
            next.acceptScope != settings.acceptScope
        settings = next
        settingsStore.save(next)
        engine?.invalidate()
        if (structural) {
            // Re-classify what is on screen: a scope change can turn a viable buffer into a
            // dead one (or the reverse) without the learner typing anything.
            current?.let { prefixState = engine?.classify(buffer, it) ?: PrefixState.EMPTY }
            eligibleRemaining = engine?.eligibleCount(mode) ?: 0
        }
    }

    fun toggleFullCodeOnly() {
        val on = !settings.fullCodeOnly
        updateSettings(settings.withFullCodeOnly(on))
        pushNotice(
            if (on) "仅全码：只有最长编码才算正确" else "已关闭仅全码",
            Notice.Tone.INFO,
        )
    }

    // ------------------------------------------------------------------------ 错题集 (FR-20)

    /** Add every character found in [input]; returns a human report for the UI. */
    fun addToMistakeSet(input: String): String {
        val e = engine ?: return "尚未载入完成"
        val chars = repository.splitInput(input).distinct()
        if (chars.isEmpty()) return "请输入要添加的汉字"
        var added = 0
        var already = 0
        val rejected = ArrayList<String>()
        for (c in chars) {
            when (val r = e.addToMistakeSet(c)) {
                is AddResult.Added -> if (r.alreadyInSet) already++ else added++
                is AddResult.Rejected -> rejected += c
            }
        }
        if (added > 0 || already > 0) markDirty(immediate = true)
        refreshDerived()
        val sb = StringBuilder()
        if (added > 0) sb.append("已添加 $added 个字")
        if (already > 0) sb.append(if (sb.isEmpty()) "" else "，").append("$already 个已在错题集")
        if (rejected.isNotEmpty()) {
            sb.append(if (sb.isEmpty()) "" else "；")
                .append("无五笔编码：").append(rejected.joinToString(""))
        }
        return sb.toString().ifEmpty { "没有可添加的内容" }
    }

    fun removeFromMistakeSet(char: String) {
        engine?.removeFromMistakeSet(char)
        markDirty(immediate = true)
        refreshDerived()
    }

    fun lookup(char: String): CharEntry? = engine?.entryFor(char)

    fun progressFor(char: String): CharProgress = progress.get(char)

    /** The characters the 错题集 screen should list, enriched with their codes. */
    fun mistakeEntries(): List<Pair<CharProgress, CharEntry?>> =
        mistakes.map { it to lookup(it.char) }

    // ----------------------------------------------------------------------------- resets

    fun resetPassed() {
        val e = engine ?: return
        progress.replaceAll(e.clearPassed())
        markDirty(immediate = true)
        engine?.invalidate()
        refreshDerived()
        engine?.forgetLastSampled()
        nextCharacter()
        pushNotice("已重置全部“已通过”记录", Notice.Tone.INFO)
    }

    fun resetMistakes() {
        val e = engine ?: return
        progress.replaceAll(e.clearMistakes())
        markDirty(immediate = true)
        engine?.invalidate()
        refreshDerived()
        nextCharacter()
        pushNotice("已清空错题集", Notice.Tone.INFO)
    }

    fun resetEverything() {
        val e = engine ?: return
        progress.replaceAll(e.clearAll())
        markDirty(immediate = true)
        engine?.invalidate()
        session = SessionStats()
        refreshDerived()
        engine?.forgetLastSampled()
        nextCharacter()
        pushNotice("已重置全部进度", Notice.Tone.INFO)
    }

    // ----------------------------------------------------------------------------- export

    /** Copy `progress.tsv` to the app's external files dir and return it for sharing. */
    fun exportProgress(): File? {
        flushNow()
        val src = File(progressFilePath)
        if (!src.isFile) {
            pushNotice("还没有可导出的进度文件", Notice.Tone.BAD)
            return null
        }
        return try {
            val dir = File(appContext.getExternalFilesDir(null), "export").apply { mkdirs() }
            val dest = File(dir, "progress.tsv")
            src.copyTo(dest, overwrite = true)
            dest
        } catch (t: Throwable) {
            pushNotice("导出失败：${t.message}", Notice.Tone.BAD)
            null
        }
    }

    fun shareIntentFor(file: File): Intent {
        val uri = FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/tab-separated-values"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun startShare(file: File) {
        val chosen = Intent.createChooser(shareIntentFor(file), "导出进度").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            appContext.startActivity(chosen)
        } catch (t: Throwable) {
            pushNotice("没有可以接收文件的应用；文件已保存到 ${file.absolutePath}", Notice.Tone.INFO)
        }
    }

    suspend fun readAttribution(): String = repository.readAsset("ATTRIBUTION.txt")

    suspend fun readManifest(): String = repository.readAsset("ASSET_MANIFEST.txt")

    companion object {
        private const val MAX_BUFFER = 4
        private const val FLUSH_DEBOUNCE_MS = 400L
        const val EXPLAIN_BASE = "https://hantang.github.io/search-wubi/?char="
    }
}


