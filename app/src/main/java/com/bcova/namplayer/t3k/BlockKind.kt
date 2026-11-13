package com.bcova.namplayer.t3k

/**
 * The kinds of blocks you can add to the signal chain. The kind decides which TONE3000 tones
 * are shown when browsing or searching for that block (gear types + format).
 * Any block can still load any .nam or .wav file.
 */
enum class BlockKind(
    val label: String,
    val hint: String,
    val gears: Set<String>,
    val format: String?,
) {
    PEDAL("Pedal", "Overdrive, fuzz, boost…", setOf("pedal"), Tone.FORMAT_NAM),
    AMP("Amp", "Amp head or full rig", setOf("amp", "amp-cab"), Tone.FORMAT_NAM),
    CAB("Cab", "Cabinet impulse response (skip for Amp + Cab tones)", emptySet(), Tone.FORMAT_IR),
    OUTBOARD("Outboard", "Preamps, compressors, studio gear", setOf("outboard"), Tone.FORMAT_NAM),
    SPACE("Space", "Rooms and reverbs", setOf("space"), null),
    ANY("Any", "Anything from TONE3000", emptySet(), null),
}

/** The chain block a tone is being chosen for. */
data class BlockTarget(val blockId: Long, val kind: BlockKind)
