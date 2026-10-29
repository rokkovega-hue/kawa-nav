package io.github.darthbob.kawanav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * Reading calimoto's notification: which text is the instruction, where the distance is, and the
 * arrow a drawable name stands for. The wordings are GraphHopper's (calimoto routes with it); the
 * notification layout itself is not known yet, so the tests cover the layouts it could have.
 */
public class CalimotoParserTest {

    private static CalimotoParser.Instruction pick(String... texts) {
        return CalimotoParser.pick(Arrays.asList(texts), false);
    }

    // ------------------------------------------------------------------ texts

    @Test
    public void instructionAndDistanceInOneLine() {
        CalimotoParser.Instruction i = pick("In 300 m rechts abbiegen", "Ankunft 14:32 · 45 km");
        assertEquals("In 300 m rechts abbiegen", i.text);
        assertEquals(300, i.distanceM);
        assertEquals(2, MapsParser.parseTurn(i.text, false));
    }

    @Test
    public void distanceAsTitleInstructionAsText() {
        CalimotoParser.Instruction i = pick("1,2 km", "Links halten");
        assertEquals("Links halten", i.text);
        assertEquals(1200, i.distanceM);
        assertEquals(9, MapsParser.parseTurn(i.text, false));
    }

    @Test
    public void inFrontOfADistanceIsStillADistanceOnly() {
        assertEquals(500, pick("Scharf rechts abbiegen", "In 500 m").distanceM);
        assertEquals(500, pick("Scharf rechts abbiegen", "nach 500 m").distanceM);
    }

    @Test
    public void theArrivalLineIsNeverTheInstruction() {
        // "Ankunft" is a destination word: in an arrival line it must not turn into the flag
        CalimotoParser.Instruction i = pick("calimoto", "Ankunft 14:32", "200 m");
        assertEquals("", i.text);
        assertEquals(200, i.distanceM);
    }

    @Test
    public void theRemainingDistanceIsNotTheDistanceToTheTurn() {
        CalimotoParser.Instruction i = pick("Rechts abbiegen auf Hauptstraße", "Noch 45 km bis zum Ziel · 52 min");
        assertEquals(-1, i.distanceM);
        assertEquals(-1, pick("Rechts abbiegen", "Verbleibende Strecke 45 km").distanceM);
    }

    @Test
    public void trackingOnlyHasNoInstruction() {
        CalimotoParser.Instruction i = pick("calimoto", "Aufzeichnung läuft", "12,3 km · 00:25 h");
        assertEquals("", i.text);
        assertEquals(-1, i.distanceM);
    }

    @Test
    public void graphHopperWordings() {
        assertEquals(31, MapsParser.parseTurn("Im Kreisverkehr Ausfahrt 2 nehmen", false));
        assertEquals(32, MapsParser.parseTurn("At roundabout, take exit 3", false));
        assertEquals(20, MapsParser.parseTurn("Im Kreisverkehr Ausfahrt 3 nehmen", true));
        assertEquals(5, MapsParser.parseTurn("Leicht links abbiegen", false));
        assertEquals(4, MapsParser.parseTurn("Scharf rechts abbiegen auf B2", false));
        assertEquals(7, MapsParser.parseTurn("Wenden", false));
        assertEquals(43, MapsParser.parseTurn("Ziel erreicht", false));
        assertEquals(0, MapsParser.parseTurn("Dem Straßenverlauf von Hauptstraße folgen", false));
        assertEquals(0, MapsParser.parseTurn("Geradeaus weiterfahren", false));
        assertEquals(10, MapsParser.parseTurn("Keep right", false));
    }

    @Test
    public void clockAndDistanceOnlyHelpers() {
        assertTrue(CalimotoParser.isClockLine("Ankunft 9:05"));
        assertFalse(CalimotoParser.isClockLine("In 300 m rechts"));
        assertTrue(CalimotoParser.isDistanceOnly("300 m"));
        assertTrue(CalimotoParser.isDistanceOnly("In 1,5 km"));
        assertFalse(CalimotoParser.isDistanceOnly("Rechts abbiegen in 300 m"));
        assertFalse(CalimotoParser.isDistanceOnly("45 km · 12 km"));
    }

    // ------------------------------------------------------------------ drawable names

    @Test
    public void namesOfManeuverArrows() {
        assertEquals(Integer.valueOf(1), CalimotoParser.turnFromName("ic_turn_left", false));
        assertEquals(Integer.valueOf(2), CalimotoParser.turnFromName("ic_turn_right_24dp", false));
        assertEquals(Integer.valueOf(3), CalimotoParser.turnFromName("maneuver_turn_sharp_left", false));
        assertEquals(Integer.valueOf(6), CalimotoParser.turnFromName("nav_turn_slight_right", false));
        assertEquals(Integer.valueOf(9), CalimotoParser.turnFromName("ic_navi_keep_left", false));
        assertEquals(Integer.valueOf(15), CalimotoParser.turnFromName("maneuver_off_ramp_right", false));
        assertEquals(Integer.valueOf(12), CalimotoParser.turnFromName("maneuver_on_ramp_left", false));
        assertEquals(Integer.valueOf(7), CalimotoParser.turnFromName("ic_uturn", false));
        assertEquals(Integer.valueOf(8), CalimotoParser.turnFromName("ic_u_turn_right", false));
        assertEquals(Integer.valueOf(43), CalimotoParser.turnFromName("ic_navi_destination", false));
        assertEquals(Integer.valueOf(0), CalimotoParser.turnFromName("ic_direction_straight", false));
    }

    @Test
    public void roundaboutNames() {
        assertEquals(Integer.valueOf(32), CalimotoParser.turnFromName("ic_roundabout_exit_3", false));
        assertEquals(Integer.valueOf(19), CalimotoParser.turnFromName("ic_roundabout_exit_2", true));
        assertEquals(Integer.valueOf(20), CalimotoParser.turnFromName("roundabout_cw_3", false));
        assertEquals(Integer.valueOf(30), CalimotoParser.turnFromName("maneuver_roundabout_ccw_right", false));
        assertEquals(Integer.valueOf(IconMemory.NOT_A_MANEUVER), CalimotoParser.turnFromName("ic_roundabout", false));
    }

    @Test
    public void namesThatAreNoManeuver() {
        assertNull(CalimotoParser.turnFromName("ic_launcher", false));
        assertNull(CalimotoParser.turnFromName("ic_notification", false));
        assertNull(CalimotoParser.turnFromName("ic_arrow_left", false));      // a back arrow
        assertNull(CalimotoParser.turnFromName("ic_brightness", false));
        assertNull(CalimotoParser.turnFromName("ic_return_left", false));
        assertNull(CalimotoParser.turnFromName("a1b", false));                // obfuscated names
        assertNull(CalimotoParser.turnFromName(null, false));
    }
}
