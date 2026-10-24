package com.eldablos.budswidget

enum class NoiseMode(val wire: Int, val label: String) {
    OFF(0, "Off"),
    ANC(1, "ANC"),
    AMBIENT(2, "Ambient");

    companion object {
        fun fromWire(value: Int): NoiseMode? = entries.firstOrNull { it.wire == value }
    }
}

/** Galaxy Buds generations differ in which listening modes they support and how they're set. */
enum class BudsModel(val modes: List<NoiseMode>) {
    ORIGINAL(listOf(NoiseMode.OFF, NoiseMode.AMBIENT)),
    PLUS(listOf(NoiseMode.OFF, NoiseMode.AMBIENT)),
    LIVE(listOf(NoiseMode.OFF, NoiseMode.ANC)),
    MODERN(listOf(NoiseMode.OFF, NoiseMode.ANC, NoiseMode.AMBIENT));

    /** The 2019 Galaxy Buds use different frame markers from every later model. */
    val legacyFraming: Boolean get() = this == ORIGINAL

    companion object {
        fun fromName(name: String?): BudsModel {
            val n = name?.lowercase()?.trim() ?: return MODERN
            return when {
                "live" in n -> LIVE
                "buds+" in n || "buds plus" in n -> PLUS
                n == "galaxy buds" || n.startsWith("galaxy buds (") -> ORIGINAL
                else -> MODERN
            }
        }
    }
}
