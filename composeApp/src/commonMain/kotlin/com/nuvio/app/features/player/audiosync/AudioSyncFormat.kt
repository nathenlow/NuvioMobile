package com.nuvio.app.features.player.audiosync

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** [this] with exactly [decimals] decimals, for logs. Kotlin's common library has no `format`. */
internal fun Double.toFixed(decimals: Int): String {
    val factor = 10.0.pow(decimals).roundToLong()
    val scaled = (abs(this) * factor).roundToLong()
    val fraction = (scaled % factor).toString().padStart(decimals, '0')
    val sign = if (this < 0 && scaled != 0L) "-" else ""
    return "$sign${scaled / factor}.$fraction"
}
