package app.prft.lightctrl.domain

import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * High-precision converter between Profoto tenth-stop power scale (1..100, representing 0.1..10.0),
 * physical Watt-Seconds ($W = W_{\max} \cdot 2^{P-10}$), and standard photographic power fractions (1/1..1/512).
 */
object PowerScaleConverter {

    private val FULL_STOP_FRACTIONS = mapOf(
        10 to "1/1",
        9 to "1/2",
        8 to "1/4",
        7 to "1/8",
        6 to "1/16",
        5 to "1/32",
        4 to "1/64",
        3 to "1/128",
        2 to "1/256",
        1 to "1/512",
    )

    /**
     * Calculates absolute energy in Watt-Seconds for a fixture with [maxWs] rating
     * at the given [tenthStops] power setting (1..100, where 100 = 10.0 f-stops).
     */
    fun calculateWattSeconds(maxWs: Int, tenthStops: Int): Double {
        require(maxWs > 0) { "maxWs must be > 0: $maxWs" }
        val clamped = tenthStops.coerceIn(1, 100)
        val p = clamped / 10.0
        return maxWs * 2.0.pow(p - 10.0)
    }

    /**
     * Formats energy in Watt-Seconds cleanly:
     * - >= 100 Ws: rounded to integer (e.g. "500 Ws", "125 Ws")
     * - 10..99.9 Ws: up to 1 decimal place without trailing .0 (e.g. "62.5 Ws", "50 Ws")
     * - < 10 Ws: 1 decimal place (e.g. "1.0 Ws", "0.5 Ws", "0.2 Ws")
     */
    fun formatWattSeconds(maxWs: Int, tenthStops: Int): String {
        val ws = calculateWattSeconds(maxWs, tenthStops)
        return when {
            ws >= 100.0 -> "${ws.roundToInt()} Ws"
            ws >= 10.0 -> {
                val rounded = (ws * 10.0).roundToInt() / 10.0
                if (rounded == rounded.toLong().toDouble()) "${rounded.toLong()} Ws"
                else String.format(Locale.US, "%.1f Ws", rounded)
            }
            else -> String.format(Locale.US, "%.1f Ws", ws)
        }
    }

    /**
     * Formats photographic power fraction for [tenthStops] (1..100):
     * - Full stops (100, 90, ..., 10): "1/1", "1/2", ..., "1/512"
     * - Intermediate tenths (10..99): "1/4 + 0.3 EV", "1/8 + 0.5 EV"
     * - Below 1.0 floor (1..9): "1/512 - 0.9 EV", "1/512 - 0.5 EV"
     */
    fun formatPowerFraction(tenthStops: Int): String {
        val clamped = tenthStops.coerceIn(1, 100)
        val fullStop = clamped / 10
        val remTenths = clamped % 10

        return when {
            remTenths == 0 -> FULL_STOP_FRACTIONS[fullStop] ?: "1/512"
            fullStop >= 1 -> {
                val base = FULL_STOP_FRACTIONS[fullStop] ?: "1/512"
                "$base + 0.$remTenths EV"
            }
            else -> {
                val delta = 10 - clamped
                "1/512 - 0.$delta EV"
            }
        }
    }

    /**
     * Formats the standard 1.0..10.0 scale readout (e.g. "7.0", "8.3", "10.0", "0.1").
     */
    fun formatScale(tenthStops: Int): String {
        val clamped = tenthStops.coerceIn(1, 100)
        return String.format(Locale.US, "%.1f", clamped / 10.0)
    }

    /**
     * Combines standard scale, Watt-Seconds (if [maxWs] provided), and fraction into a single readout:
     * - With maxWs: "7.0 (62.5 Ws · 1/8)" or "8.3 (154 Ws · 1/4 + 0.3 EV)"
     * - Without maxWs: "7.0 (1/8)" or "8.3 (1/4 + 0.3 EV)"
     */
    fun formatDualPowerReadout(tenthStops: Int, maxWs: Int?): String {
        val scale = formatScale(tenthStops)
        val fraction = formatPowerFraction(tenthStops)
        return if (maxWs != null && maxWs > 0) {
            val ws = formatWattSeconds(maxWs, tenthStops)
            "$scale ($ws · $fraction)"
        } else {
            "$scale ($fraction)"
        }
    }
}
