package com.custom.ridenavlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalAndParserTest {

    @Test
    fun testAsciiTransliterationForClusterLcd() {
        assertEquals(
            "Joao & Marcia - 1a Ligacao (Sao Pedro)",
            BajajProtocol.sanitizeForClusterLcd("João & Márcia — 1ª Ligação (São Pedro) 📞")
        )
        assertEquals(
            "Acao Coracao Onibus Urubu",
            BajajProtocol.sanitizeForClusterLcd("Ação Coração Ônibus Ürubu")
        )
    }

    @Test
    fun testStartupWithoutDistanceDoesNotInventOneKmStraight() {
        val parsed = GoogleMapsNotificationParser.parse(
            title = "Google Maps",
            text = "Tv. São Pedro",
            subText = ""
        )
        assertNull("Should not invent fake 1000m straight on startup without distance", parsed)
    }

    @Test
    fun testSlightRightFromIconWithStreetOnlyText() {
        // Exact top screenshot scenario: "40 m" / "Tv. São Pedro" with Slight Right icon
        val parsed = GoogleMapsNotificationParser.parse(
            title = "40 m",
            text = "Tv. São Pedro",
            fallbackTotalDistanceMeters = 9900,
            iconManeuverCode = BajajProtocol.ARROW_SLIGHT_RIGHT_SOLID,
            iconConfidenceScore = 0.88f
        )
        assertNotNull(parsed)
        assertEquals(BajajProtocol.ARROW_SLIGHT_RIGHT_SOLID, parsed!!.solidManeuverCode)
        assertEquals(BajajProtocol.ARROW_SLIGHT_RIGHT_BLINK, parsed.maneuverCode)
        assertEquals(40, parsed.distanceMeters)
        assertEquals(9900, parsed.totalDistanceMeters)
        assertFalse(parsed.isArrival)
    }

    @Test
    fun testDestinationIconAt80mShowsStraightAndSyncsStaleEtd() {
        // Exact bottom screenshot scenario: "80 m" / "Tv. São Pedro, 35 - Barra Nova"
        // Shows straight arrow while approaching (>40m), marks isApproachingDestination=true,
        // and syncs ETD to 80m instead of stale 9.8km.
        val parsed = GoogleMapsNotificationParser.parse(
            title = "80 m",
            text = "Tv. São Pedro, 35 - Barra Nova",
            fallbackTotalDistanceMeters = 9800,
            iconManeuverCode = BajajProtocol.ARROW_DESTINATION_SOLID,
            iconConfidenceScore = 0.94f
        )
        assertNotNull(parsed)
        assertEquals(BajajProtocol.ARROW_STRAIGHT_SOLID, parsed!!.solidManeuverCode)
        assertTrue(parsed.isApproachingDestination)
        assertEquals(80, parsed.distanceMeters)
        assertEquals(80, parsed.totalDistanceMeters)
        assertFalse(parsed.isArrival)
    }

    @Test
    fun testDestinationIconAt35mTriggersExplicitArrivalEvenWithStaleFallbackEtd() {
        val parsed = GoogleMapsNotificationParser.parse(
            title = "35 m",
            text = "Tv. São Pedro, 35 - Barra Nova",
            fallbackTotalDistanceMeters = 9800,
            iconManeuverCode = BajajProtocol.ARROW_DESTINATION_SOLID,
            iconConfidenceScore = 0.94f
        )
        assertNotNull(parsed)
        assertTrue("Expected explicit arrival at 35m with destination icon", parsed!!.isArrival)
        assertEquals(BajajProtocol.ARROW_DESTINATION_BLINK, parsed.maneuverCode)
        assertEquals(0, parsed.distanceMeters)
        assertEquals(0, parsed.totalDistanceMeters)
    }
}

