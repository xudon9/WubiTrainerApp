package com.xudong.wubitrainer.data

/**
 * Parsers for the two generated assets (PRD §2.2). Pure string-in/objects-out so the format
 * contract is covered by JVM unit tests without an Android device.
 *
 * Both asset formats are TSV with `#` comments and a `#`-prefixed header row:
 *
 *   wubi_chars.tsv   char \t codes \t rank_modern \t freq_modern \t rank_overall \t freq_overall
 *   wubi_full.tsv    char \t codes
 */
object WubiAssets {

    private val CODE_RE = Regex("^[a-z]{1,4}$")

    /** Characters whose codes are trustworthy: a single code point, letters only. */
    private fun validCodes(raw: String): List<String>? {
        val codes = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (codes.isEmpty()) return null
        if (codes.any { !CODE_RE.matches(it) }) return null
        // Re-derive the invariant rather than trusting the file: length ascending, then lexical.
        return codes.distinct().sortedWith(compareBy({ it.length }, { it }))
    }

    /** Rows that are neither blank nor comments. */
    private fun dataLines(text: String): Sequence<String> =
        text.lineSequence().map { it.trimEnd('\r', '\n') }
            .filter { it.isNotBlank() && !it.startsWith("#") }

    /** Parse `wubi_chars.tsv`, preserving file order (which is rank order in the Modern list). */
    fun parseCharsTsv(text: String): List<CharEntry> {
        val out = ArrayList<CharEntry>(16_384)
        val seen = HashSet<String>(16_384)
        for (line in dataLines(text)) {
            val f = line.split('\t')
            if (f.size < 2) continue
            val char = f[0]
            if (char.codePointCount(0, char.length) != 1) continue
            val codes = validCodes(f[1]) ?: continue
            if (!seen.add(char)) continue
            out += CharEntry(
                char = char,
                codes = codes,
                rankModern = f.getOrNull(2)?.toIntOrNull() ?: 0,
                freqModern = f.getOrNull(3)?.toLongOrNull() ?: 0L,
                rankOverall = f.getOrNull(4)?.toIntOrNull() ?: 0,
                freqOverall = f.getOrNull(5)?.toLongOrNull() ?: 0L,
            )
        }
        return out
    }

    /** Parse `wubi_full.tsv` into a plain lookup used for hand-added characters. */
    fun parseFullTsv(text: String): Map<String, List<String>> {
        val out = HashMap<String, List<String>>(1 shl 17)
        for (line in dataLines(text)) {
            val f = line.split('\t')
            if (f.size < 2) continue
            val char = f[0]
            if (char.codePointCount(0, char.length) != 1) continue
            val codes = validCodes(f[1]) ?: continue
            out.putIfAbsent(char, codes)
        }
        return out
    }

    /**
     * Resolve one character's codes: the frequency pool first, then the full 70,944-character
     * table. Returns null only for something that has no Wubi86 code at all, which the 错题集
     * reports back to the learner rather than silently ignoring.
     *
     * Kept here as a pure function (rather than inline in the Android repository) so the exact
     * resolution order can be tested against the real shipped assets without a device.
     */
    fun resolve(
        char: String,
        pool: Map<String, CharEntry>,
        full: Map<String, List<String>>?,
    ): CharEntry? {
        // The add box is labelled 添加汉字 for a reason: the source dictionary also carries
        // punctuation and Latin-1 symbols ("¤" zzhb, "à" zzpy). Those are not characters anyone
        // learns Wubi for, so they are refused before any lookup happens.
        if (!isHan(char)) return null
        pool[char]?.let { return it }
        val codes = full?.get(char) ?: return null
        // Outside the frequency pool: no rank and no count, so sampling weight falls back to
        // 1.0 and the character stays reachable rather than impossible.
        return CharEntry(char, codes, 0, 0L, 0, 0L)
    }

    /** True for a single character in the CJK Unified Ideographs (and extensions) blocks. */
    fun isHan(char: String): Boolean {
        if (char.isEmpty() || char.codePointCount(0, char.length) != 1) return false
        return Character.UnicodeScript.of(char.codePointAt(0)) == Character.UnicodeScript.HAN
    }

    /** Split a user-typed string into the individual Han characters it contains. */
    fun splitCharacters(input: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < input.length) {
            val cp = input.codePointAt(i)
            val n = Character.charCount(cp)
            val s = input.substring(i, i + n)
            if (s.isNotBlank()) out += s
            i += n
        }
        return out
    }
}
