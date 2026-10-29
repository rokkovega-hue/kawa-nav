package io.github.darthbob.kawanav;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads calimoto's navigation notification: which of its texts is the instruction, where the distance
 * to the maneuver is, and which arrow a calimoto drawable stands for (from its resource name).
 *
 * <p>Nothing here is verified against a real calimoto notification yet. calimoto routes with
 * GraphHopper, whose instruction wording ("Rechts abbiegen", "Links halten", "Im Kreisverkehr
 * Ausfahrt 2 nehmen", "Ziel erreicht", "Turn sharp left", "At roundabout, take exit 2") is the same
 * kind of text {@link MapsParser} already reads, so the turn comes from there. What calimoto's
 * notification looks like (which field holds the instruction, whether the arrow is a resource icon,
 * a bitmap or a custom layout) is not known, so this looks at all of them, picks the text that names
 * a maneuver, and logs the whole notification once per navigation (see MapsListener) so a real ride
 * shows what to fix.
 *
 * <p>Pure Java, no Android: tested on the JVM.
 */
public final class CalimotoParser {
    private CalimotoParser() {}

    public static final String PACKAGE = "com.calimoto.calimoto";
    public static final String APP_NAME = "calimoto";

    /** A clock time ("14:32", "1:05 h"): an arrival or duration line, never the next maneuver. */
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)\\d{1,2}:\\d{2}(?!\\d)");

    /** What was read from the notification's texts. */
    public static final class Instruction {
        /** The text that names the maneuver, or "" when none does. */
        public final String text;
        /** Distance to the maneuver in metres, or -1. */
        public final int distanceM;

        Instruction(String text, int distanceM) {
            this.text = text;
            this.distanceM = distanceM;
        }
    }

    /**
     * The instruction and the distance among the notification's texts, in the order the notification
     * gives them (title first). The instruction is the first text that names a maneuver; the distance
     * is the one in that text, else a text that holds nothing but a distance ("300 m", "In 1,2 km").
     * Lines with a clock time are arrival or duration lines and are skipped for both, and so is a
     * distance in a long text that names no maneuver (the remaining distance of the route).
     */
    public static Instruction pick(List<String> texts, boolean leftHandTraffic) {
        String instruction = "";
        for (String t : texts) {
            if (t == null || isClockLine(t)) continue;
            if (MapsParser.parseTurn(t, leftHandTraffic) != KawaProtocol.TURN_NONE) {
                instruction = t;
                break;
            }
        }
        int dist = instruction.isEmpty() ? -1 : MapsParser.parseDistance(instruction);
        if (dist < 0) {
            for (String t : texts) {
                if (t == null || isClockLine(t)) continue;
                if (isDistanceOnly(t)) {
                    dist = MapsParser.parseDistance(t);
                    break;
                }
            }
        }
        return new Instruction(instruction, dist);
    }

    static boolean isClockLine(String t) {
        return CLOCK.matcher(t).find();
    }

    /** "300 m", "In 1,2 km", "nach 500 m": a distance and at most a short word around it. */
    static boolean isDistanceOnly(String t) {
        Matcher m = MapsParser.DISTANCE.matcher(t);
        if (!m.find()) return false;
        String rest = (t.substring(0, m.start()) + " " + t.substring(m.end())).trim();
        int letters = 0;
        for (int i = 0; i < rest.length(); i++) if (Character.isLetter(rest.charAt(i))) letters++;
        return letters <= 5 && !MapsParser.DISTANCE.matcher(rest).find();
    }

    // ------------------------------------------------------------------ resource names

    /** Words that make a drawable name a maneuver arrow rather than an app or UI icon. */
    private static final String[] CONTEXT = {"maneuver", "manoeuvre", "maneuvre", "turn", "direction",
            "instruction", "navi", "roundabout", "rbt", "kreisverkehr", "uturn", "u_turn", "keep", "fork",
            "ramp", "merge", "sharp", "slight", "straight", "destination", "arrive", "arrival", "finish",
            "waypoint", "depart", "continue", "abbiegen", "pfeil"};

    /**
     * The turn id a calimoto drawable stands for, judged by its resource name
     * ("ic_turn_sharp_left", "maneuver_roundabout_exit_3", "nav_keep_right"), or null when the name
     * is no maneuver arrow (the app icon, a UI symbol). {@link IconMemory#NOT_A_MANEUVER} for an
     * arrow without direction information (a roundabout without exit), so the text decides.
     */
    public static Integer turnFromName(String name, boolean leftHandTraffic) {
        if (name == null || name.isEmpty()) return null;
        String n = name.toLowerCase(Locale.ROOT)
                .replaceAll("\\d+\\s*dp", "_")              // ic_turn_left_24dp
                .replace("copyright", "_").replace("bright", "_").replace("return", "_");
        if (!containsAny(n, CONTEXT)) return null;
        boolean left = n.contains("left") || n.contains("links");
        boolean right = n.contains("right") || n.contains("rechts");
        if (left && right) return null;                     // "left_right" toggles and the like

        if (containsAny(n, "roundabout", "rbt", "kreisverkehr", "rotary")) {
            boolean ccw = containsAny(n, "ccw", "counterclockwise", "counter_clockwise", "anticlockwise");
            boolean cw = !ccw && (hasToken(n, "cw") || n.contains("clockwise"));
            boolean clockwise = cw || (!ccw && leftHandTraffic);
            int exit = roundaboutExitIn(n);
            if (exit < 1) {
                // the exit's direction, as Maps' arrows give it: right = 1st, straight = 2nd, left = 3rd
                // on the usual 4-arm roundabout (mirrored when it turns clockwise)
                if (containsAny(n, "uturn", "u_turn", "back")) exit = 4;
                else if (right) exit = clockwise ? 3 : 1;
                else if (left) exit = clockwise ? 1 : 3;
                else if (containsAny(n, "straight", "ahead")) exit = 2;
                else return IconMemory.NOT_A_MANEUVER;
            }
            return MapsParser.roundaboutTurn(exit, clockwise);
        }
        if (containsAny(n, "uturn", "u_turn", "turnaround", "turn_around", "wenden")) {
            if (right) return 8;
            if (left) return 7;
            return leftHandTraffic ? 8 : 7;
        }
        if (containsAny(n, "destination", "arrive", "arrival", "finish", "ziel", "waypoint")) return 43;
        if (n.contains("depart")) return 42;
        if (left || right) {
            if (n.contains("sharp")) return left ? 3 : 4;
            if (containsAny(n, "slight", "bear", "half")) return left ? 5 : 6;
            if (containsAny(n, "off_ramp", "offramp", "exit")) return left ? 14 : 15;
            if (containsAny(n, "on_ramp", "onramp", "ramp")) return left ? 12 : 13;
            if (n.contains("merge")) return left ? 16 : 17;
            if (containsAny(n, "keep", "fork", "stay")) return left ? 9 : 10;
            return left ? 1 : 2;
        }
        if (containsAny(n, "keep", "fork") && containsAny(n, "middle", "center", "centre")) return 11;
        if (n.contains("merge")) return 16;
        if (containsAny(n, "straight", "continue", "ahead", "name_change")) return 0;
        return null;
    }

    /** "roundabout_exit_3", "rbt_3", "roundabout3": the exit number in a drawable name, or -1. */
    private static int roundaboutExitIn(String n) {
        for (Pattern p : EXIT_IN_NAME) {
            Matcher m = p.matcher(n);
            if (!m.find()) continue;
            int v = Integer.parseInt(m.group(1));
            return v >= 1 && v <= 12 ? v : -1;
        }
        return -1;
    }

    private static final Pattern[] EXIT_IN_NAME = {
            Pattern.compile("(?:exit|ausfahrt)_?(\\d{1,2})(?!\\d)"),
            Pattern.compile("(?:roundabout|rbt|kreisverkehr|rotary)_?(\\d{1,2})(?!\\d)"),
            Pattern.compile("(?:^|_)(\\d{1,2})(?:_|$)"),
    };

    private static boolean hasToken(String n, String token) {
        for (String t : n.split("[^a-z0-9]+")) if (t.equals(token)) return true;
        return false;
    }

    private static boolean containsAny(String n, String... words) {
        for (String w : words) if (n.contains(w)) return true;
        return false;
    }
}
