package com.nuvio.app.features.downloads

import com.nuvio.app.features.streams.StreamItem
import kotlinx.serialization.Serializable

/** What a piece of text in a stream's name or description tells about it. */
@Serializable
enum class MarkerMeaning { Cached, NotCached, Debrid, Usenet, Http, Torrent }

/** [text] shows up in the stream's name or description when the stream is [meaning]. */
@Serializable
data class StreamMarker(val text: String, val meaning: MarkerMeaning)

/**
 * The name and description templates the user's addon formats streams with, and the markers read from
 * them. Add-ons such as AIOStreams only send the rendered text, so this is how the app can tell what a
 * stream is (for example that "⏳" means not cached).
 */
@Serializable
data class StreamFormatSetup(
    val nameTemplate: String = "",
    val descriptionTemplate: String = "",
    val markers: List<StreamMarker> = emptyList(),
)

/** Reads markers from an AIOStreams custom formatter template. */
internal object AioFormatterParser {

    fun parse(template: String): List<StreamMarker> =
        buildList { collect(straightQuotes(template), this, depth = 0) }.distinct()

    // A phone keyboard or a document turns " and ' into curly quotes; the template needs plain ones.
    private fun straightQuotes(text: String): String =
        text.replace('\u201C', '"').replace('\u201D', '"').replace('\u2018', '\'').replace('\u2019', '\'')

    private fun collect(template: String, out: MutableList<StreamMarker>, depth: Int) {
        if (depth > MAX_DEPTH) return
        var i = 0
        while (i < template.length) {
            if (template[i] != '{') { i++; continue }
            val end = matchingBrace(template, i) ?: return
            val inner = template.substring(i + 1, end)
            readConditional(inner)?.let { (field, modifiers, branches) ->
                markersFor(field, modifiers, branches, out)
                // A branch can hold a conditional of its own.
                branches.forEach { collect(it, out, depth + 1) }
            } ?: collect(inner, out, depth + 1)
            i = end + 1
        }
    }

    /** The index of the brace closing the one at [open], or null when it is never closed. */
    private fun matchingBrace(text: String, open: Int): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
        }
        return null
    }

    private data class Conditional(val field: String, val modifiers: List<String>, val branches: List<String>)

    private operator fun Conditional.component1() = field
    private operator fun Conditional.component2() = modifiers
    private operator fun Conditional.component3() = branches

    /** `field::modifier["a"||"b"]` as its parts, or null when [inner] has no branches. */
    private fun readConditional(inner: String): Conditional? {
        var depth = 0
        var bracket = -1
        for (i in inner.indices) {
            when (inner[i]) {
                '{' -> depth++
                '}' -> depth--
                '[' -> if (depth == 0) { bracket = i; break }
            }
        }
        if (bracket < 0 || !inner.trimEnd().endsWith("]")) return null
        val header = inner.substring(0, bracket).split("::").map { it.trim() }
        val field = header.firstOrNull()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val body = inner.substring(bracket + 1, inner.trimEnd().length - 1)
        return Conditional(field, header.drop(1), readBranches(body))
    }

    /** The quoted branches of `"a"||"b"||"c"`. A quote inside braces or escaped as \" is text. */
    private fun readBranches(body: String): List<String> {
        val branches = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        var depth = 0
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                !inQuote -> if (c == '"') { inQuote = true; current.clear() }
                c == '\\' && i + 1 < body.length && body[i + 1] == '"' -> { current.append('"'); i++ }
                c == '{' -> { depth++; current.append(c) }
                c == '}' -> { depth--; current.append(c) }
                c == '"' && depth == 0 -> { inQuote = false; branches += current.toString() }
                else -> current.append(c)
            }
            i++
        }
        return branches
    }

    private fun markersFor(field: String, modifiers: List<String>, branches: List<String>, out: MutableList<StreamMarker>) {
        fun add(text: String?, meaning: MarkerMeaning) {
            val clean = text?.trim()
            // Text that is itself built from fields cannot be looked for.
            if (!clean.isNullOrEmpty() && '{' !in clean) out += StreamMarker(clean, meaning)
        }
        val condition = modifiers.firstOrNull()?.lowercase()
        when (field) {
            "service.cached" -> when (condition) {
                null -> { add(branches.getOrNull(0), MarkerMeaning.Cached); add(branches.getOrNull(1), MarkerMeaning.NotCached) }
                "istrue" -> { add(branches.getOrNull(0), MarkerMeaning.Cached); add(branches.getOrNull(1), MarkerMeaning.NotCached) }
                "isfalse" -> { add(branches.getOrNull(0), MarkerMeaning.NotCached); add(branches.getOrNull(1), MarkerMeaning.Cached) }
            }
            "stream.type" -> {
                val value = condition?.takeIf { it.startsWith("=") }?.drop(1)?.trim()?.trim('\'', '"')
                val meaning = when (value) {
                    "p2p" -> MarkerMeaning.Torrent
                    "http" -> MarkerMeaning.Http
                    "debrid" -> MarkerMeaning.Debrid
                    "usenet" -> MarkerMeaning.Usenet
                    else -> null
                }
                if (meaning != null) add(branches.getOrNull(0), meaning)
            }
        }
    }

    private const val MAX_DEPTH = 6
}

private fun StreamItem.formattedText(): String =
    listOfNotNull(name, title, description).joinToString("\n")

/** The meanings whose marker appears in this stream's name or description. */
internal fun StreamItem.markerMeanings(markers: List<StreamMarker>): Set<MarkerMeaning> {
    if (markers.isEmpty()) return emptySet()
    val text = formattedText()
    return markers.filter { text.contains(it.text, ignoreCase = true) }.mapTo(linkedSetOf()) { it.meaning }
}

/** Cached or not as the formatter markers say, or null when none of them is in the stream's text. */
internal fun Set<MarkerMeaning>.cachedByMarkers(): Boolean? = when {
    // "Uncached" contains "cached", so the negative marker decides first.
    MarkerMeaning.NotCached in this -> false
    MarkerMeaning.Cached in this -> true
    else -> null
}

internal fun Set<MarkerMeaning>.sourceByMarkers(): ProfileSource? = when {
    MarkerMeaning.Usenet in this -> ProfileSource.Usenet
    MarkerMeaning.Debrid in this -> ProfileSource.Debrid
    MarkerMeaning.Torrent in this -> ProfileSource.Torrent
    MarkerMeaning.Http in this -> ProfileSource.Http
    else -> null
}
