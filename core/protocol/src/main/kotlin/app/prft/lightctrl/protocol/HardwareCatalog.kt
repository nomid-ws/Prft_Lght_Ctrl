package app.prft.lightctrl.protocol

/**
 * Device archetype determining which studio control surface and protocol capabilities apply.
 */
enum class DeviceArchetype {
    /** B10X Plus (500Ws), B10X (250Ws), B10, B10 Plus, A2 (100Ws), D30, B20, B30, Pro-D3 */
    MONOLIGHT,
    /** Connect Pro 100-channel AirX OLED Master Remote (IDs 161..166) */
    CONNECT_PRO_REMOTE,
    /** Original buttonless Profoto Connect AirTTL Remote (IDs 150..154) */
    CONNECT_REMOTE,
    /** A10 / C1 / C1 Plus on-camera or smartphone lights */
    COMPACT_FLASH,
    /** Multi-head studio pack (Pro-11, Pro-B3) */
    PACK_GENERATOR,
    /** Continuous cinema fixtures (L600/L1600/ProPanel) */
    CINEMA_LED,
}

data class HardwareModelSpec(
    val productId: Int,
    val shortName: String,
    val marketingName: String,
    val ratedOutputLabel: String,
    val archetype: DeviceArchetype,
    val supportsVideoDimming: Boolean = false,
    val supportsBiColorCct: Boolean = false,
    val defaultMinKelvin: Int = 3000,
    val defaultMaxKelvin: Int = 6500,
    val supportsContinuousPowerModes: Boolean = false,
    val supportsCaptureModeToggle: Boolean = false,
    val supportsIrSlave: Boolean = false,
    val supportsDisplayOrientation: Boolean = false,
    val supportsAcStandPower: Boolean = false,
    val maxAirChannelDefault: Int = 100,
    val usesLegacyV1Queries: Boolean = false,
    val maxWattSeconds: Int? = null,
)

/**
 * Authoritative 38-model Profoto hardware catalog extracted from Y7/e0.java & j6/a.java,
 * with enriched capability profiles for B10X Plus ("B10X Pro"), B10X, A2, Connect Pro, and Connect.
 */
object HardwareCatalog {

    private val specsById: Map<Int, HardwareModelSpec> = listOf(
        // Compact Monolight: Profoto A2 (100 Ws, fixed 3500K LED modeling light)
        HardwareModelSpec(
            productId = 36,
            shortName = "A2",
            marketingName = "Profoto A2",
            ratedOutputLabel = "100 Ws",
            archetype = DeviceArchetype.MONOLIGHT,
            supportsVideoDimming = false,
            supportsBiColorCct = false,
            defaultMinKelvin = 3500,
            defaultMaxKelvin = 3500,
            supportsContinuousPowerModes = true,
            supportsCaptureModeToggle = false,
            supportsIrSlave = false,
            supportsDisplayOrientation = false,
            supportsAcStandPower = false,
            maxAirChannelDefault = 100,
            maxWattSeconds = 100,
        ),

        // B10 & B10X / B10X Pro / Plus Series (500 Ws & 250 Ws)
        HardwareModelSpec(
            productId = 143,
            shortName = "B10X Pro",
            marketingName = "Profoto B10X Pro / Plus",
            ratedOutputLabel = "500 Ws · 3250 lm",
            archetype = DeviceArchetype.MONOLIGHT,
            supportsVideoDimming = true,
            supportsBiColorCct = true,
            defaultMinKelvin = 3000,
            defaultMaxKelvin = 6500,
            supportsContinuousPowerModes = true,
            supportsCaptureModeToggle = true,
            supportsIrSlave = true,
            supportsDisplayOrientation = true,
            supportsAcStandPower = true,
            maxAirChannelDefault = 100,
            maxWattSeconds = 500,
        ),
        HardwareModelSpec(
            productId = 144,
            shortName = "B10X",
            marketingName = "Profoto B10X",
            ratedOutputLabel = "250 Ws · 3250 lm",
            archetype = DeviceArchetype.MONOLIGHT,
            supportsVideoDimming = true,
            supportsBiColorCct = true,
            defaultMinKelvin = 3000,
            defaultMaxKelvin = 6500,
            supportsContinuousPowerModes = true,
            supportsCaptureModeToggle = true,
            supportsIrSlave = true,
            supportsDisplayOrientation = true,
            supportsAcStandPower = true,
            maxAirChannelDefault = 100,
            maxWattSeconds = 250,
        ),
        HardwareModelSpec(
            productId = 140,
            shortName = "B10",
            marketingName = "Profoto B10",
            ratedOutputLabel = "250 Ws · 2500 lm",
            archetype = DeviceArchetype.MONOLIGHT,
            supportsVideoDimming = true,
            supportsBiColorCct = true,
            defaultMinKelvin = 3000,
            defaultMaxKelvin = 6500,
            supportsContinuousPowerModes = true,
            supportsCaptureModeToggle = true,
            supportsIrSlave = true,
            supportsDisplayOrientation = true,
            supportsAcStandPower = true,
            maxAirChannelDefault = 20,
            maxWattSeconds = 250,
        ),
        HardwareModelSpec(
            productId = 141,
            shortName = "B10 Plus",
            marketingName = "Profoto B10 Plus",
            ratedOutputLabel = "500 Ws · 2500 lm",
            archetype = DeviceArchetype.MONOLIGHT,
            supportsVideoDimming = true,
            supportsBiColorCct = true,
            defaultMinKelvin = 3000,
            defaultMaxKelvin = 6500,
            supportsContinuousPowerModes = true,
            supportsCaptureModeToggle = true,
            supportsIrSlave = true,
            supportsDisplayOrientation = true,
            supportsAcStandPower = true,
            maxAirChannelDefault = 20,
            maxWattSeconds = 500,
        ),

        // Profoto Connect Pro Remotes (161..166)
        HardwareModelSpec(161, "Connect Pro", "Profoto Connect Pro (Canon)", "AirX Remote", DeviceArchetype.CONNECT_PRO_REMOTE, maxAirChannelDefault = 100),
        HardwareModelSpec(162, "Connect Pro", "Profoto Connect Pro (Nikon)", "AirX Remote", DeviceArchetype.CONNECT_PRO_REMOTE, maxAirChannelDefault = 100),
        HardwareModelSpec(163, "Connect Pro", "Profoto Connect Pro (Sony)", "AirX Remote", DeviceArchetype.CONNECT_PRO_REMOTE, maxAirChannelDefault = 100),
        HardwareModelSpec(164, "Connect Pro", "Profoto Connect Pro (Fujifilm)", "AirX Remote", DeviceArchetype.CONNECT_PRO_REMOTE, maxAirChannelDefault = 100),
        HardwareModelSpec(165, "Connect Pro", "Profoto Connect Pro (Leica)", "AirX Remote", DeviceArchetype.CONNECT_PRO_REMOTE, maxAirChannelDefault = 100),
        HardwareModelSpec(166, "Connect Pro", "Profoto Connect Pro (ISO)", "AirX Remote", DeviceArchetype.CONNECT_PRO_REMOTE, maxAirChannelDefault = 100),

        // Profoto Connect Remotes (150..154)
        HardwareModelSpec(150, "Connect", "Profoto Connect (Canon)", "AirTTL Remote", DeviceArchetype.CONNECT_REMOTE, maxAirChannelDefault = 8, usesLegacyV1Queries = true),
        HardwareModelSpec(151, "Connect", "Profoto Connect (Nikon)", "AirTTL Remote", DeviceArchetype.CONNECT_REMOTE, maxAirChannelDefault = 8, usesLegacyV1Queries = true),
        HardwareModelSpec(152, "Connect", "Profoto Connect (Sony)", "AirTTL Remote", DeviceArchetype.CONNECT_REMOTE, maxAirChannelDefault = 8, usesLegacyV1Queries = true),
        HardwareModelSpec(153, "Connect", "Profoto Connect (Olympus/OM)", "AirTTL Remote", DeviceArchetype.CONNECT_REMOTE, maxAirChannelDefault = 8, usesLegacyV1Queries = true),
        HardwareModelSpec(154, "Connect", "Profoto Connect (Fujifilm)", "AirTTL Remote", DeviceArchetype.CONNECT_REMOTE, maxAirChannelDefault = 8, usesLegacyV1Queries = true),

        // Additional Profoto Studio & Location Catalog
        HardwareModelSpec(4, "Pro-11", "Profoto Pro-11", "2400 Ws", DeviceArchetype.PACK_GENERATOR, supportsIrSlave = true, supportsAcStandPower = true, maxWattSeconds = 2400),
        HardwareModelSpec(30, "A10", "Profoto A10", "76 Ws", DeviceArchetype.COMPACT_FLASH, maxWattSeconds = 76),
        HardwareModelSpec(31, "A10", "Profoto A10 (Canon)", "76 Ws", DeviceArchetype.COMPACT_FLASH, maxWattSeconds = 76),
        HardwareModelSpec(32, "A10", "Profoto A10 (Nikon)", "76 Ws", DeviceArchetype.COMPACT_FLASH, maxWattSeconds = 76),
        HardwareModelSpec(33, "A10", "Profoto A10 (Sony)", "76 Ws", DeviceArchetype.COMPACT_FLASH, maxWattSeconds = 76),
        HardwareModelSpec(35, "A10", "Profoto A10 (Fujifilm)", "76 Ws", DeviceArchetype.COMPACT_FLASH, maxWattSeconds = 76),
        HardwareModelSpec(48, "Pro-D3", "Profoto Pro-D3", "AC Strobe", DeviceArchetype.MONOLIGHT, supportsBiColorCct = true, supportsAcStandPower = true, maxWattSeconds = 750),
        HardwareModelSpec(49, "Pro-D3 750", "Profoto Pro-D3 750", "750 Ws", DeviceArchetype.MONOLIGHT, supportsBiColorCct = true, supportsAcStandPower = true, maxWattSeconds = 750),
        HardwareModelSpec(50, "Pro-D3 1250", "Profoto Pro-D3 1250", "1250 Ws", DeviceArchetype.MONOLIGHT, supportsBiColorCct = true, supportsAcStandPower = true, maxWattSeconds = 1250),
        HardwareModelSpec(51, "Pro-B3", "Profoto Pro-B3", "750 Ws", DeviceArchetype.PACK_GENERATOR, supportsBiColorCct = true, maxWattSeconds = 750),
        HardwareModelSpec(52, "D30", "Profoto D30", "500 Ws", DeviceArchetype.MONOLIGHT, supportsBiColorCct = true, supportsAcStandPower = true, maxWattSeconds = 500),
        HardwareModelSpec(53, "B20", "Profoto B20", "250 Ws", DeviceArchetype.MONOLIGHT, supportsBiColorCct = true, supportsAcStandPower = true, maxWattSeconds = 250),
        HardwareModelSpec(54, "B30", "Profoto B30", "500 Ws", DeviceArchetype.MONOLIGHT, supportsBiColorCct = true, supportsAcStandPower = true, maxWattSeconds = 500),
        HardwareModelSpec(145, "C1", "Profoto C1", "1600 lm", DeviceArchetype.COMPACT_FLASH, supportsBiColorCct = true),
        HardwareModelSpec(146, "C1 Plus", "Profoto C1 Plus", "4300 lm", DeviceArchetype.COMPACT_FLASH, supportsBiColorCct = true),
        HardwareModelSpec(174, "ProPanel 3x2", "Profoto ProPanel 3x2 Bi-Color", "600 W", DeviceArchetype.CINEMA_LED, supportsBiColorCct = true),
        HardwareModelSpec(175, "ProPanel 3x2", "Profoto ProPanel 3x2 RGB", "600 W", DeviceArchetype.CINEMA_LED, supportsBiColorCct = true),
        HardwareModelSpec(176, "L1600D", "Profoto L1600D", "1600 W", DeviceArchetype.CINEMA_LED),
        HardwareModelSpec(180, "L600D", "Profoto L600D", "600 W", DeviceArchetype.CINEMA_LED),
        HardwareModelSpec(181, "L600X", "Profoto L600X", "600 W", DeviceArchetype.CINEMA_LED, supportsBiColorCct = true),
        HardwareModelSpec(182, "L600C", "Profoto L600C", "600 W", DeviceArchetype.CINEMA_LED, supportsBiColorCct = true),
    ).associateBy { it.productId }

    fun resolve(productId: Int, fallbackBleName: String? = null): HardwareModelSpec {
        specsById[productId]?.let { return it }

        // Name-based heuristic before HardwareId (Opcode 74) finishes hydrating
        val upper = fallbackBleName?.uppercase().orEmpty()
        return when {
            "CONNECT PRO" in upper || "CONNECTPRO" in upper || upper.startsWith("CP-") -> specsById.getValue(166)
            "CONNECT" in upper -> HardwareModelSpec(
                productId = 0,
                shortName = "Connect",
                marketingName = "Profoto Connect Remote",
                ratedOutputLabel = "AirX / AirTTL",
                archetype = DeviceArchetype.CONNECT_REMOTE,
                maxAirChannelDefault = 100,
                usesLegacyV1Queries = true,
            )
            "A10" in upper || "A1X" in upper || "A1" in upper -> specsById.getValue(30)
            "B10X PLUS" in upper || "B10X PRO" in upper -> specsById.getValue(143)
            "B10X" in upper -> specsById.getValue(144)
            "B10 PLUS" in upper -> specsById.getValue(141)
            "B10" in upper -> specsById.getValue(140)
            "B1X" in upper || "B1" in upper -> HardwareModelSpec(
                productId = 0,
                shortName = "B1X",
                marketingName = fallbackBleName?.takeIf { it.isNotBlank() } ?: "Profoto B1X",
                ratedOutputLabel = "500 Ws",
                archetype = DeviceArchetype.MONOLIGHT,
                maxWattSeconds = 500,
            )
            "B2" in upper -> HardwareModelSpec(
                productId = 0,
                shortName = "B2",
                marketingName = fallbackBleName?.takeIf { it.isNotBlank() } ?: "Profoto B2",
                ratedOutputLabel = "250 Ws",
                archetype = DeviceArchetype.MONOLIGHT,
                maxWattSeconds = 250,
            )
            "D2 1000" in upper || "D2_1000" in upper -> HardwareModelSpec(
                productId = 0,
                shortName = "D2 1000",
                marketingName = fallbackBleName?.takeIf { it.isNotBlank() } ?: "Profoto D2 1000",
                ratedOutputLabel = "1000 Ws",
                archetype = DeviceArchetype.MONOLIGHT,
                maxWattSeconds = 1000,
            )
            "D2 500" in upper || "D2_500" in upper || "D2" in upper -> HardwareModelSpec(
                productId = 0,
                shortName = "D2 500",
                marketingName = fallbackBleName?.takeIf { it.isNotBlank() } ?: "Profoto D2 500",
                ratedOutputLabel = "500 Ws",
                archetype = DeviceArchetype.MONOLIGHT,
                maxWattSeconds = 500,
            )
            "PRO-10" in upper || "PRO10" in upper || "PRO-11" in upper || "PRO11" in upper -> specsById.getValue(4)
            "A2" in upper -> specsById.getValue(36)
            else -> HardwareModelSpec(
                productId = productId,
                shortName = fallbackBleName?.takeIf { it.isNotBlank() } ?: "Profoto Light",
                marketingName = fallbackBleName?.takeIf { it.isNotBlank() } ?: "Profoto Device ($productId)",
                ratedOutputLabel = "AirX",
                archetype = DeviceArchetype.MONOLIGHT,
                supportsBiColorCct = true,
                supportsContinuousPowerModes = true,
            )
        }
    }
}
