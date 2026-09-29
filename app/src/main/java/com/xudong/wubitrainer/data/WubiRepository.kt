package com.xudong.wubitrainer.data

import android.content.res.AssetManager
import com.xudong.wubitrainer.data.WubiAssets.splitCharacters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AssetInfo(
    val poolRows: Int,
    val fullRows: Int,
    val attributed: Boolean,
)

/**
 * Loads the generated assets and answers "what are this character's codes?" (PRD §3.1).
 *
 * Two files, loaded at different times on purpose:
 *  - `wubi_chars.tsv` (12,041 rows) is the ranked practice pool and is needed before the
 *    first drill, so it is loaded first;
 *  - `wubi_full.tsv` (70,944 rows) exists only so that a character the learner adds by hand
 *    can be found even when it is nowhere near the frequency pool. It is loaded straight
 *    after, still off the main thread, and the UI only needs [fullTableReady] to know
 *    whether hand-entry is fully armed yet.
 */
class WubiRepository(private val assets: AssetManager) {

    private var poolByModern: List<CharEntry> = emptyList()
    private var poolByOverall: List<CharEntry> = emptyList()
    private var byChar: Map<String, CharEntry> = emptyMap()
    private var fullTable: Map<String, List<String>>? = null

    var info: AssetInfo = AssetInfo(0, 0, false)
        private set

    /** The pool sorted by Modern rank — the default ordering. */
    val poolModern: List<CharEntry> get() = poolByModern

    /** The same characters sorted by the site's default (mixed/literary) ranking. */
    val poolOverall: List<CharEntry> get() = poolByOverall

    val poolSize: Int get() = poolByModern.size

    val fullTableReady: Boolean get() = fullTable != null

    val fullTableSize: Int get() = fullTable?.size ?: 0

    suspend fun loadPool() = withContext(Dispatchers.IO) {
        val text = assets.open("wubi_chars.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parsed = WubiAssets.parseCharsTsv(text)
        poolByModern = parsed.sortedWith(
            compareBy({ if (it.rankModern > 0) it.rankModern else Int.MAX_VALUE }, { it.char }),
        )
        poolByOverall = parsed.sortedWith(
            compareBy({ if (it.rankOverall > 0) it.rankOverall else Int.MAX_VALUE }, { it.char }),
        )
        byChar = parsed.associateBy { it.char }
        info = info.copy(poolRows = parsed.size, attributed = hasAsset("ATTRIBUTION.txt"))
    }

    suspend fun loadFullTable() = withContext(Dispatchers.IO) {
        if (fullTable != null) return@withContext
        val text = assets.open("wubi_full.tsv").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val parsed = WubiAssets.parseFullTsv(text)
        fullTable = parsed
        info = info.copy(fullRows = parsed.size)
    }

    /**
     * Resolve one character's codes: the frequency pool first, then the full table. Returns
     * null only for something that has no Wubi86 code at all, which the 错题集 reports back
     * to the learner rather than silently ignoring.
     */
    fun lookup(char: String): CharEntry? = WubiAssets.resolve(char, byChar, fullTable)

    /** Does this asset ship in the APK? Used to report attribution presence, not to gate logic. */
    private fun hasAsset(name: String): Boolean =
        runCatching { assets.open(name).close() }.isSuccess

    /** Split arbitrary user input into the characters it contains, for the add box. */
    fun splitInput(input: String): List<String> = splitCharacters(input)

    suspend fun readAsset(name: String): String = withContext(Dispatchers.IO) {
        runCatching {
            assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrDefault("")
    }
}
