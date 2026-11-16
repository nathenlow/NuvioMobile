package com.nuvio.app.features.downloads

/** One option of a tag condition, found in a stream's name, file name or the addon's parsed fields. */
internal class ProfileTag(val id: String, val label: String, pattern: String) {
    // Whole tokens only, so "dts" does not match inside a word.
    val regex = Regex("(?<![a-z0-9])(?:$pattern)(?![a-z0-9])", RegexOption.IGNORE_CASE)
}

/**
 * The options of the Quality, Codec, HDR, Bit depth, Audio and Edition conditions. Labels are the
 * names release files use, which are the same in every language.
 */
internal object ProfileTags {
    private const val SEP = "[ ._-]?"

    val quality = listOf(
        ProfileTag("bluray", "BluRay", "blu${SEP}ray|bd${SEP}rip|br${SEP}rip|bdmv|bd25|bd50"),
        ProfileTag("remux", "Remux", "remux"),
        ProfileTag("webdl", "WEB-DL", "web${SEP}dl|web(?!${SEP}rip)"),
        ProfileTag("webrip", "WEBRip", "web${SEP}rip"),
        ProfileTag("hdtv", "HDTV", "hdtv|pdtv|tvrip|hdtvrip"),
        ProfileTag("dvd", "DVD", "dvd${SEP}rip|dvdr|dvd5|dvd9|dvd"),
        ProfileTag("cam", "CAM / screener", "cam|camrip|hdcam|hdts|telesync|telecine|hdtc|screener|dvdscr"),
    )

    val codec = listOf(
        ProfileTag("hevc", "HEVC / x265", "hevc|x265|h\\.?265"),
        ProfileTag("avc", "AVC / x264", "avc|x264|h\\.?264"),
        ProfileTag("av1", "AV1", "av1"),
        ProfileTag("xvid", "XviD / DivX", "xvid|divx"),
    )

    val hdr = listOf(
        ProfileTag("dv", "Dolby Vision", "dolby${SEP}vision|dovi|dv"),
        ProfileTag("hdr10plus", "HDR10+", "hdr10\\+|hdr10plus|hdr10p"),
        ProfileTag("hdr", "HDR10 / HDR", "hdr10(?!\\+|plus|p)|hdr"),
        // Matched as "none of the others", see matchesAny.
        ProfileTag("sdr", "SDR", "sdr"),
    )

    val bitDepth = listOf(
        ProfileTag("10bit", "10-bit", "10${SEP}bit|hi10p?"),
        ProfileTag("8bit", "8-bit", "8${SEP}bit"),
    )

    val audio = listOf(
        ProfileTag("atmos", "Atmos", "atmos"),
        ProfileTag("truehd", "TrueHD", "true${SEP}hd"),
        ProfileTag("dtshd", "DTS-HD / DTS:X", "dts${SEP}hd(?:${SEP}ma)?|dts${SEP}x|dts:x|dts${SEP}ma"),
        ProfileTag("dts", "DTS", "dts"),
        ProfileTag("ddplus", "DD+ / E-AC3", "ddp(?:\\d(?:\\.\\d)?)?|dd\\+|e${SEP}ac${SEP}3|dolby${SEP}digital${SEP}plus"),
        ProfileTag("dd", "DD / AC3", "dd(?!\\+)(?:\\d(?:\\.\\d)?)?|ac${SEP}3|dolby${SEP}digital(?!${SEP}plus)"),
        ProfileTag("aac", "AAC", "aac(?:\\d(?:\\.\\d)?)?"),
        ProfileTag("flac", "FLAC", "flac"),
        ProfileTag("opus", "Opus", "opus"),
    )

    val edition = listOf(
        ProfileTag("extended", "Extended", "extended"),
        ProfileTag("directors", "Director's Cut", "director'?s${SEP}cut"),
        ProfileTag("imax", "IMAX", "imax"),
        ProfileTag("unrated", "Unrated / Uncut", "unrated|uncut"),
        ProfileTag("remastered", "Remastered", "remaster(?:ed)?"),
        ProfileTag("theatrical", "Theatrical", "theatrical"),
    )

    fun optionsFor(type: ProfileConditionType): List<ProfileTag> = when (type) {
        ProfileConditionType.Quality -> quality
        ProfileConditionType.Codec -> codec
        ProfileConditionType.Hdr -> hdr
        ProfileConditionType.BitDepth -> bitDepth
        ProfileConditionType.Audio -> audio
        ProfileConditionType.Edition -> edition
        else -> emptyList()
    }

    fun label(type: ProfileConditionType, id: String): String =
        optionsFor(type).firstOrNull { it.id == id }?.label ?: id

    /** True when [text] (lowercased stream text) shows any of the options [ids]. */
    fun matchesAny(type: ProfileConditionType, ids: List<String>, text: String): Boolean {
        val options = optionsFor(type)
        return ids.any { id ->
            if (type == ProfileConditionType.Hdr && id == "sdr") {
                // A file is SDR when it says so, or says nothing about HDR.
                options.first { it.id == "sdr" }.regex.containsMatchIn(text) ||
                    options.filter { it.id != "sdr" }.none { it.regex.containsMatchIn(text) }
            } else {
                options.firstOrNull { it.id == id }?.regex?.containsMatchIn(text) == true
            }
        }
    }
}
