package com.bcova.namplayer.t3k

import java.net.URLEncoder

/** One choice in a filter, e.g. value "amp-cab" shown as "Amp + Cab". */
data class Option(val value: String, val label: String)

object SearchOptions {
    val sorts = listOf(
        Option("best-match", "Best match"),
        Option("trending", "Trending"),
        Option("newest", "Newest"),
        Option("downloads-all-time", "Most downloaded"),
        Option("oldest", "Oldest"),
    )
    val gears = listOf(
        Option("amp", "Amp"),
        Option("amp-cab", "Amp + Cab"),
        Option("pedal", "Pedal"),
        Option("cab", "Cab"),
        Option("outboard", "Outboard"),
        Option("space", "Space"),
        Option("experimental", "Experimental"),
    )
    val sizes = listOf(
        Option("standard", "Standard"),
        Option("lite", "Lite"),
        Option("feather", "Feather"),
        Option("nano", "Nano"),
        Option("custom", "Custom"),
    )
    val formats = listOf(
        Option(Tone.FORMAT_NAM, "NAM"),
        Option(Tone.FORMAT_IR, "IR"),
    )
}

/**
 * Everything the user has chosen on the Search screen, turned into a /tones/search request.
 * See https://www.tone3000.com/api#tones (Search).
 */
data class SearchFilters(
    val query: String = "",
    val sort: String? = null, // null = TONE3000 default (best match with text, trending without)
    val gears: Set<String> = emptySet(),
    val sizes: Set<String> = emptySet(),
    val tags: Set<String> = emptySet(),
    val makes: Set<String> = emptySet(),
    val format: String? = null,
    val a2Only: Boolean = false,
    val calibrated: Boolean = false,
    val verified: Boolean = false,
) {
    val activeFilterCount: Int
        get() = gears.size + sizes.size + tags.size + makes.size +
            listOf(format != null, a2Only, calibrated, verified).count { it }

    fun toQuery(page: Int): String {
        val params = mutableListOf("page" to page.toString(), "page_size" to "25")
        if (query.isNotBlank()) params += "query" to query.trim()
        sort?.let { params += "sort" to it }
        // Multiple values are underscore-separated, except creators (comma).
        if (gears.isNotEmpty()) params += "gears" to gears.sorted().joinToString("_")
        if (sizes.isNotEmpty()) params += "sizes" to sizes.sorted().joinToString("_")
        if (tags.isNotEmpty()) params += "tags" to tags.sorted().joinToString("_")
        if (makes.isNotEmpty()) params += "makes" to makes.sorted().joinToString("_")
        format?.let { params += "format" to it }
        // Without this, TONE3000 hides A2-only tones. "2" = only A2; omitted = A1 + custom.
        if (a2Only) params += "architecture" to "2"
        if (calibrated) params += "calibrated" to "true"
        if (verified) params += "verified" to "true"
        return params.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }
}
