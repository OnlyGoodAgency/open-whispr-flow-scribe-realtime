package dev.pivisolutions.dictus.core.whisper

import java.text.Normalizer
import java.util.Locale

/** Bounded vocabulary evidence; never stores complete dictations or screen contents. */
object DictationLearning {
    const val MAX_TERMS = 100
    const val MAX_CANDIDATES = 200
    const val MAX_CONTEXT = 4000
    const val MAX_EDITOR = 8000
    private val words = Regex("[\\p{L}][\\p{L}\\p{M}\\p{N}]*(?:[.'’-][\\p{L}\\p{M}\\p{N}]+)*")
    private val common = ("a an the i me my we our you your he him his she her it its they them their " +
        "this that these those there here is are was were be been being am do does did have has had " +
        "will would can could should shall may might must and or but so if as of for from to in on at " +
        "by with about into out up down no not yes now then than very really just also all any some " +
        "one two three four five six seven eight nine ten first second third zero twenty thirty " +
        "hundred thousand million please thanks hello hi use send call report meet paid pay dollars " +
        "um uh er ah like know mean sort kind basically right yeah probably roughly think " +
        "le la les un une des du de et est en au aux je tu il elle nous vous ils elles " +
        "ang ng mga sa na ako ikaw siya ito iyon at ay po opo hindi oo").split(' ').toSet()

    fun canonical(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)

    fun validTerm(value: String): Boolean = value.isNotBlank() && value.length <= 80 &&
        value.none { it.code < 32 } && value.any(Char::isLetter)

    fun candidates(text: String, screen: Boolean = false): List<String> = words.findAll(text)
        .map { it.value.trimEnd('.', '-') }
        .filter { validTerm(it) && it.length >= 2 && canonical(it) !in common }
        .filter { !screen || it.any(Char::isUpperCase) }
        .distinctBy(::canonical).take(50).toList()

    fun decodeAliases(value: String): List<DictationVocabularyTerm> = value.lineSequence()
        .mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2 && parts.all(::validTerm)) DictationVocabularyTerm(parts[0], parts[1]) else null
        }.distinctBy { canonical(it.spoken) }.take(MAX_TERMS).toList()

    fun encodeAliases(terms: List<DictationVocabularyTerm>): String = terms.takeLast(MAX_TERMS)
        .joinToString("\n") { "${it.spoken}\t${it.written}" }

    /** Learn close spelling fixes, not semantic rewrites, quantities, or contextual homophones. */
    fun correction(original: String, edited: String): DictationVocabularyTerm? {
        val before = words.findAll(original).toList()
        val after = words.findAll(edited).toList()
        var first = 0
        while (first < minOf(before.size, after.size) && before[first].value == after[first].value) first++
        var tail = 0
        while (tail < minOf(before.size, after.size) - first && before[before.lastIndex-tail].value == after[after.lastIndex-tail].value) tail++
        val old = before.subList(first, before.size-tail)
        val new = after.subList(first, after.size-tail)
        if (old.size !in 1..4 || new.size !in 1..4) return null
        val spoken = original.substring(old.first().range.first, old.last().range.last+1)
        val written = edited.substring(new.first().range.first, new.last().range.last+1)
        if (!validTerm(spoken) || !validTerm(written) || spoken.any(Char::isDigit) || written.any(Char::isDigit)) return null
        val a = canonical(spoken).filter(Char::isLetter)
        val b = canonical(written).filter(Char::isLetter)
        if (a.length < 2 || b.length < 2 || a in common || b in common) return null
        if (distance(a, b) > maxOf(1, maxOf(a.length, b.length) / 3)) return null
        return DictationVocabularyTerm(spoken, written)
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length+1) { it }
        for (i in a.indices) {
            val next = IntArray(b.length+1)
            next[0] = i+1
            for (j in b.indices) next[j+1] = minOf(next[j]+1, previous[j+1]+1, previous[j] + if (a[i] == b[j]) 0 else 1)
            previous = next
        }
        return previous.last()
    }
}

/** Tracks only edits to a recently inserted dictation; field text remains in memory. */
class DictationEditTracker {
    private var identity = ""
    private var prefix = ""
    private var suffix = ""
    private var original = ""
    private var expiresAt = 0L
    private var lastAccepted = ""
    private var generation = 0L

    fun clear() { identity = ""; prefix = ""; suffix = ""; original = ""; lastAccepted = ""; expiresAt = 0 }

    fun arm(field: String, fullText: String, start: Int, length: Int, now: Long) {
        clear()
        if (fullText.length > DictationLearning.MAX_EDITOR || start < 0 || length <= 0 || start+length > fullText.length) return
        identity = field
        prefix = fullText.take(start)
        suffix = fullText.drop(start+length)
        original = fullText.substring(start, start+length)
        lastAccepted = original
        generation = RecentDictation.generation
        expiresAt = now + 120_000
    }

    fun settle(field: String, fullText: String, now: Long): DictationVocabularyTerm? {
        if (identity.isEmpty()) return null
        if (field != identity || generation != RecentDictation.generation || now > expiresAt || fullText.length > DictationLearning.MAX_EDITOR ||
            !fullText.startsWith(prefix) || !fullText.endsWith(suffix) || fullText.length < prefix.length+suffix.length) {
            clear(); return null
        }
        val edited = fullText.substring(prefix.length, fullText.length-suffix.length)
        if (edited == lastAccepted) return null
        lastAccepted = edited
        // Keep the original until a complete spelling correction has settled.
        val term = DictationLearning.correction(original, edited)
        if (term != null) original = edited
        return term
    }
}

/** The last output can be recognized when pasted; expires and is never written to preferences. */
object RecentDictation {
    var generation = 0L
        private set
    private var text = ""
    private var expiresAt = 0L
    fun publish(value: String, now: Long) {
        generation++
        text = value.trim().takeIf { it.length <= DictationLearning.MAX_EDITOR }.orEmpty()
        expiresAt = now + 300_000
    }
    fun clear() { text = ""; expiresAt = 0; generation++ }
    fun findInsertion(before: String, after: String, now: Long): IntRange? {
        if (now > expiresAt || text.isBlank() || after.length > DictationLearning.MAX_EDITOR) { clear(); return null }
        val start = after.indexOf(text)
        if (start < 0 || after.indexOf(text, start+1) >= 0) return null
        // Only arm for an actual insertion of this output, not a field that already contained it.
        val without = after.removeRange(start, start+text.length)
        if (without.trim() != before.trim()) return null
        return start until start+text.length
    }
}

/** Accessibility supplies active-window text only while the user has enabled context hints. */
object DictationScreenContext {
    private var owner: Any? = null
    private var provider: ((String?) -> String)? = null
    fun register(source: Any, read: (String?) -> String) { owner = source; provider = read }
    fun unregister(source: Any) { if (owner === source) { owner = null; provider = null } }
    fun read(packageName: String?): String = runCatching { provider?.invoke(packageName).orEmpty().take(DictationLearning.MAX_CONTEXT) }.getOrDefault("")
}
