package io.github.darthbob.kawanav;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;

import java.util.HashMap;
import java.util.Map;

/**
 * calimoto's maneuver arrows. Unlike Google Maps, calimoto's drawable names are not known, so they are
 * found by name: every drawable of the installed calimoto app whose resource name reads like a
 * maneuver ({@link CalimotoParser#turnFromName}) is rendered and hashed like Maps' icons, so a bitmap
 * arrow in calimoto's notification can be matched. A notification icon that is a calimoto resource
 * is read by its name directly ({@link #fromResource}).
 *
 * <p>If calimoto's resource names are obfuscated, nothing matches and the instruction text decides.
 */
public final class CalimotoIcons {
    private CalimotoIcons() {}

    public static final String SOURCE = "calimoto icon";

    /** The name and turn of a calimoto resource icon. */
    public static final class Named {
        public final String name;
        /** Turn id, or {@link IconMemory#NOT_A_MANEUVER}. */
        public final int turn;

        Named(String name, int turn) {
            this.name = name;
            this.turn = turn;
        }
    }

    /**
     * The turn of an icon that is one of calimoto's own drawables, read from its resource name, or null
     * when the icon is no calimoto resource or its name is no maneuver (the app's logo).
     */
    static Named fromResource(Context context, Icon icon, boolean leftHandTraffic) {
        String name = resourceName(context, icon);
        if (name == null) return null;
        Integer turn = CalimotoParser.turnFromName(name, leftHandTraffic);
        return turn == null ? null : new Named(name, turn);
    }

    /** The resource name of a calimoto resource icon, or null. */
    static String resourceName(Context context, Icon icon) {
        if (icon == null || icon.getType() != Icon.TYPE_RESOURCE) return null;
        try {
            String pkg = icon.getResPackage();
            if (pkg == null || !pkg.equals(CalimotoParser.PACKAGE)) return null;
            Resources r = context.createPackageContext(pkg, 0).getResources();
            return r.getResourceEntryName(icon.getResId());
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException e) {
            return null;
        } catch (RuntimeException e) {
            Logger.log("calimoto icon name unreadable: " + e);
            return null;
        }
    }

    private static Map<String, String> library;   // hash -> drawable name
    private static long libraryVersion = -1;

    /** hash -> turn for calimoto's maneuver-like drawables (rebuilt after a calimoto update). */
    static synchronized Map<String, Integer> library(Context context, boolean leftHandTraffic) {
        long version = version(context);
        if (library == null || version != libraryVersion) {
            library = build(context, version);
            libraryVersion = version;
        }
        Map<String, Integer> out = new HashMap<>();
        for (Map.Entry<String, String> e : library.entrySet()) {
            Integer t = CalimotoParser.turnFromName(e.getValue(), leftHandTraffic);
            if (t != null) out.put(e.getKey(), t);
        }
        return out;
    }

    /** The drawable name a hash came from, for the log. */
    static synchronized String nameOf(String hash) {
        return library == null ? null : library.get(hash);
    }

    private static Map<String, String> build(Context context, long version) {
        Map<String, String> lib = new HashMap<>();
        if (version == 0) return lib;               // calimoto not installed
        int scanned = 0;
        try {
            Resources r = context.createPackageContext(CalimotoParser.PACKAGE, 0).getResources();
            // resource ids are 0x7f TT EEEE: find the drawable type, then walk its entries
            for (int type = 1; type <= 0x40; type++) {
                int base = 0x7f000000 | (type << 16);
                if (!"drawable".equals(typeName(r, base))) continue;
                int misses = 0;
                for (int entry = 0; entry < 0x10000 && misses < 256; entry++) {
                    String name;
                    try {
                        name = r.getResourceEntryName(base | entry);
                    } catch (Resources.NotFoundException e) {
                        misses++;
                        continue;
                    }
                    misses = 0;
                    scanned++;
                    if (CalimotoParser.turnFromName(name, false) == null) continue;
                    String hash = render(r, base | entry);
                    if (hash != null && !lib.containsKey(hash)) lib.put(hash, name);
                }
            }
            Logger.log("calimoto icon library: " + lib.size() + " maneuver-like drawables of " + scanned
                    + " (calimoto " + version + ")" + (lib.isEmpty() ? ": the instruction text decides" : ""));
        } catch (Exception e) {
            Logger.log("calimoto icon library unavailable: " + e);
        }
        return lib;
    }

    /** The type name of the first entry that exists near {@code base}, or null. */
    private static String typeName(Resources r, int base) {
        for (int entry = 0; entry < 8; entry++) {
            try {
                return r.getResourceTypeName(base | entry);
            } catch (Resources.NotFoundException ignored) {
                // try the next entry
            }
        }
        return null;
    }

    private static String render(Resources r, int id) {
        try {
            Drawable d = r.getDrawable(id, null);
            if (d == null) return null;
            d = d.mutate();
            d.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
            Bitmap b = Bitmap.createBitmap(126, 126, Bitmap.Config.ARGB_8888);
            d.setBounds(0, 0, 126, 126);
            d.draw(new Canvas(b));
            return IconMemory.hash(b);
        } catch (Exception e) {
            return null;                            // animated, nine-patch, theme attributes…
        }
    }

    static long version(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(CalimotoParser.PACKAGE, 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }
}
