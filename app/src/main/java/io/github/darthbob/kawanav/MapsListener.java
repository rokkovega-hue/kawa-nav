package io.github.darthbob.kawanav;

import android.app.Notification;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Watches the turn-by-turn notification of Google Maps and of calimoto and publishes the current
 * maneuver to {@link Hub}. Needs "Notification access" for KawaNav in Android settings.
 */
public class MapsListener extends NotificationListenerService {
    public static final String MAPS = "com.google.android.apps.maps";
    public static final String CALIMOTO = CalimotoParser.PACKAGE;

    private IconMemory icons;
    private String lastKey = "";
    private int navNotificationId = Integer.MIN_VALUE;
    // calimoto's own state: both apps may have a notification up at the same time
    private String calimotoKey = "";
    private int calimotoNotificationId = Integer.MIN_VALUE;

    @Override
    public void onCreate() {
        super.onCreate();
        Logger.init(this);
        icons = new IconMemory(this);
    }

    @Override
    public void onListenerConnected() {
        Logger.log("Notification access active");
        try {
            for (StatusBarNotification sbn : getActiveNotifications()) onNotificationPosted(sbn);
            // notifications that were already there: show their icon too
            for (StatusBarNotification sbn : getActiveNotifications()) dashAlert(sbn, true);
        } catch (SecurityException e) {
            Logger.log("Cannot read active notifications: " + e.getMessage());
        }
    }

    @Override
    public void onListenerDisconnected() {
        Logger.log("Notification access disconnected");
        setMaps(null);
        setCalimoto(null);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        NotifCapture.onPosted(this, sbn);   // debug builds, switched on in the hidden card
        dashAlert(sbn, true);
        if (CALIMOTO.equals(sbn.getPackageName())) {
            onCalimoto(sbn);
            return;
        }
        if (!MAPS.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        boolean nav = Notification.CATEGORY_NAVIGATION.equals(n.category)
                || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
        if (!nav) return;
        navNotificationId = sbn.getId();

        List<String> texts = new ArrayList<>();
        Bundle ex = n.extras;
        for (String k : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TEXT,
                Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT, Notification.EXTRA_INFO_TEXT,
                Notification.EXTRA_SUMMARY_TEXT}) {
            CharSequence cs = ex.getCharSequence(k);
            if (cs != null && cs.length() > 0 && !texts.contains(cs.toString())) texts.add(cs.toString());
        }

        // Maps (Android 16 live-update style) puts the maneuver arrow in the small icon as a bitmap;
        // its static app icon would be a resource, so only take bitmap small icons
        Icon arrow = n.getLargeIcon();
        if (arrow == null && n.getSmallIcon() != null && n.getSmallIcon().getType() == Icon.TYPE_BITMAP) {
            arrow = n.getSmallIcon();
        }
        Bitmap icon = loadIcon(arrow);
        if (texts.isEmpty() || icon == null) {
            // Maps may render the turn card as a custom layout; read its views instead
            Bitmap fromLayout = readRemoteViews(n.bigContentView != null ? n.bigContentView : n.contentView,
                    texts, icon == null);
            if (icon == null) icon = fromLayout;
        }

        String hash = icon != null ? IconMemory.hash(icon) : null;
        String joined = String.join(" | ", texts);
        String key = joined + "#" + hash;
        if (key.equals(lastKey)) return;
        boolean firstOfSession = lastKey.isEmpty();
        lastKey = key;

        if (firstOfSession) dumpExtras("Maps", n);

        SharedPreferences p = Hub.prefs(this);
        boolean leftHand = p.getBoolean(Hub.KEY_LEFT_HAND, false);
        // the title is the instruction ("60 m · Rechts abbiegen auf X"); the text is the ETA line
        CharSequence title = ex.getCharSequence(Notification.EXTRA_TITLE);
        String instruction = title != null && title.length() > 0 ? title.toString() : joined;
        int parsed = MapsParser.parseTurn(instruction, leftHand);
        int dist = MapsParser.parseDistance(instruction);
        IconMemory.Hit hit = icons.lookup(hash);

        // The icon shows the side Maps committed to, the text the kind it is sure about: a text that
        // names a ramp or an exit but no side must not flip an arrow that already points the right way.
        int turn = MapsParser.chooseTurn(instruction, hit == null ? null : hit.turn, leftHand);
        String how;
        if (hit == null) {
            how = turn == KawaProtocol.TURN_NONE ? "not recognised" : "text";
        } else if (turn == hit.turn) {
            how = hit.source;
        } else if (MapsParser.hasRoundaboutExitNumber(instruction)) {
            how = "text, roundabout exit number";
        } else {
            how = hit.source + " + text";
        }

        icons.collect(hash, icon, parsed, instruction);
        if (hit != null && parsed != KawaProtocol.TURN_NONE && hit.turn != parsed
                && !MapsParser.isRoundabout(instruction)) {
            Logger.log("Note: icon says " + turnName(hit.turn) + " (" + hit.source + "), text says " + turnName(parsed));
        }

        String iconInfo = icon == null ? "no icon" : "icon " + icon.getWidth() + "px #" + IconMemory.shortId(hash);
        Logger.log("Maps: \"" + joined + "\" -> " + turnName(turn) + ", " + dist + " m (" + how + ", " + iconInfo + ")");
        setMaps(new Hub.MapsTurn(turn, dist, turnName(turn) + (dist >= 0 ? " · " + dist + " m" : "") + " (" + how + ")"));
    }

    /** A call or mail/message notification appeared or went away: tell the bike (icon only, no content). */
    private void dashAlert(StatusBarNotification sbn, boolean posted) {
        if (getPackageName().equals(sbn.getPackageName())) return;
        String category = sbn.getNotification().category;
        int icon = DashAlerts.categoryOf(sbn.getPackageName(), category, sbn.isOngoing());
        if (icon == 0) return;
        String key = sbn.getPackageName() + "|" + sbn.getId() + "|" + sbn.getTag();
        boolean change = posted ? DashAlerts.onPosted(icon, key) : DashAlerts.onRemoved(icon, key);
        if (!change) return;
        boolean on = DashAlerts.active(icon) && DashAlerts.enabled(this, icon);
        BridgeService.sendDashAlert(icon, on ? DashAlerts.ADDED : DashAlerts.REMOVED);
        Logger.log("Dash " + DashAlerts.name(icon) + " icon " + (on ? "on" : "off"));
    }

    /** The rider switched a dash icon off: clear it on the bike now. */
    public static void alertsChanged(android.content.Context c) {
        for (int icon : new int[]{DashAlerts.CALL, DashAlerts.MAIL}) {
            if (DashAlerts.enabled(c, icon)) continue;
            if (DashAlerts.clear(icon)) Logger.log("Dash " + DashAlerts.name(icon) + " icon off (settings)");
            // one path for sending, so what the meter was told is recorded: else every later
            // connection would send this clear again
            int event = DashAlerts.desiredEvent(icon, false, false);
            if (event != DashAlerts.NOTHING) BridgeService.sendDashAlert(icon, event);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        NotifCapture.onRemoved(this, sbn);
        dashAlert(sbn, false);
        if (MAPS.equals(sbn.getPackageName()) && sbn.getId() == navNotificationId) {
            Logger.log("Maps navigation ended");
            lastKey = "";
            setMaps(null);
        }
        if (CALIMOTO.equals(sbn.getPackageName()) && sbn.getId() == calimotoNotificationId) {
            Logger.log("calimoto navigation ended");
            calimotoKey = "";
            calimotoNotificationId = Integer.MIN_VALUE;
            setCalimoto(null);
        }
    }

    /** Google Maps' maneuver; null clears it, but never a maneuver calimoto put there. */
    private static void setMaps(Hub.MapsTurn m) {
        if (m == null && Hub.maps != null && Hub.maps.fromCalimoto()) return;
        Hub.maps = m;
        BridgeService.poke();
    }

    /** calimoto's maneuver; null clears it, but never one Google Maps put there. */
    private static void setCalimoto(Hub.MapsTurn m) {
        if (m == null && (Hub.maps == null || !Hub.maps.fromCalimoto())) return;
        Hub.maps = m;
        BridgeService.poke();
    }

    // ------------------------------------------------------------------ calimoto

    /**
     * calimoto's navigation notification. Its layout is not known yet (see {@link CalimotoParser}), so
     * every place an instruction could be is read: the texts of the notification and of a custom
     * layout, a resource icon by its name, a bitmap arrow by comparing it with calimoto's drawables.
     * The whole notification is logged once per navigation, so the log of a real ride shows what
     * calimoto posts.
     */
    private void onCalimoto(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        boolean nav = Notification.CATEGORY_NAVIGATION.equals(n.category)
                || (n.flags & Notification.FLAG_ONGOING_EVENT) != 0
                || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0;
        if (!nav) return;
        boolean leftHand = Hub.prefs(this).getBoolean(Hub.KEY_LEFT_HAND, false);

        List<String> texts = new ArrayList<>();
        Bundle ex = n.extras;
        for (String k : new String[]{Notification.EXTRA_TITLE, Notification.EXTRA_TITLE_BIG,
                Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT,
                Notification.EXTRA_INFO_TEXT, Notification.EXTRA_SUMMARY_TEXT}) {
            addText(texts, ex.getCharSequence(k));
        }
        CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) for (CharSequence l : lines) addText(texts, l);

        // a resource icon is read by its name; a bitmap is compared with calimoto's drawables
        CalimotoIcons.Named named = CalimotoIcons.fromResource(this, n.getLargeIcon(), leftHand);
        if (named == null) named = CalimotoIcons.fromResource(this, n.getSmallIcon(), leftHand);
        Icon arrow = n.getLargeIcon();
        if (arrow != null && arrow.getType() == Icon.TYPE_RESOURCE) arrow = null;  // read by name above
        if (arrow == null && n.getSmallIcon() != null && n.getSmallIcon().getType() == Icon.TYPE_BITMAP) {
            arrow = n.getSmallIcon();
        }
        Bitmap icon = named != null ? null : loadIcon(arrow);
        // a custom layout: its texts always count, its first image is the arrow if there is none yet
        RemoteViews rv = n.bigContentView != null ? n.bigContentView : n.contentView;
        Bitmap fromLayout = readRemoteViews(rv, texts, icon == null && named == null);
        if (icon == null && named == null) icon = fromLayout;

        String hash = icon != null ? IconMemory.hash(icon) : null;
        String joined = String.join(" | ", texts);
        String key = joined + "#" + hash + "#" + (named != null ? named.name : "");
        if (key.equals(calimotoKey)) return;
        boolean firstOfSession = calimotoKey.isEmpty();
        calimotoKey = key;
        if (firstOfSession) dumpExtras("calimoto", n);

        CalimotoParser.Instruction ins = CalimotoParser.pick(texts, leftHand);
        IconMemory.Hit hit;
        if (named != null) {
            hit = named.turn == IconMemory.NOT_A_MANEUVER ? null
                    : new IconMemory.Hit(named.turn, CalimotoIcons.SOURCE + " " + named.name);
        } else {
            hit = icons.lookupCalimoto(hash, leftHand);
        }
        int turn = MapsParser.chooseTurn(ins.text, hit == null ? null : hit.turn, leftHand);
        String how = hit == null ? "text" : turn == hit.turn ? hit.source : hit.source + " + text";
        String iconInfo = named != null ? "resource " + named.name
                : icon == null ? "no icon" : "icon " + icon.getWidth() + "px #" + IconMemory.shortId(hash);

        if (turn == KawaProtocol.TURN_NONE) {
            // tracking a ride, "route is calculated", a download: no maneuver, so no navigation to show
            Logger.log("calimoto: \"" + joined + "\" -> no maneuver (" + iconInfo + ")");
            if (sbn.getId() == calimotoNotificationId) setCalimoto(null);
            return;
        }
        calimotoNotificationId = sbn.getId();
        icons.collect(hash, icon, MapsParser.parseTurn(ins.text, leftHand), ins.text.isEmpty() ? joined : ins.text);
        int dist = ins.distanceM;
        Logger.log("calimoto: \"" + joined + "\" -> " + turnName(turn) + ", " + dist + " m (" + how + ", " + iconInfo + ")");
        setCalimoto(new Hub.MapsTurn(turn, dist,
                turnName(turn) + (dist >= 0 ? " · " + dist + " m" : "") + " (calimoto, " + how + ")",
                CalimotoParser.APP_NAME));
    }

    private static void addText(List<String> texts, CharSequence cs) {
        if (cs == null || cs.length() == 0) return;
        String s = cs.toString().trim();
        if (!s.isEmpty() && !texts.contains(s)) texts.add(s);
    }

    static String turnName(int turn) {
        return turn >= 0 && turn < KawaProtocol.TURN_NAMES.length ? KawaProtocol.TURN_NAMES[turn] : "unknown";
    }

    private Bitmap loadIcon(Icon icon) {
        if (icon == null) return null;
        try {
            Drawable d = icon.loadDrawable(this);
            if (d == null) return null;
            if (d instanceof BitmapDrawable && ((BitmapDrawable) d).getBitmap() != null) {
                return ((BitmapDrawable) d).getBitmap();
            }
            return IconMemory.render(d);
        } catch (RuntimeException e) {
            Logger.log("Icon load failed: " + e);
            return null;
        }
    }

    /** Collects texts (and optionally the first image) from a custom notification layout. */
    private Bitmap readRemoteViews(RemoteViews rv, List<String> texts, boolean wantIcon) {
        if (rv == null) return null;
        Bitmap[] found = wantIcon ? new Bitmap[1] : null;
        try {
            walk(rv.apply(this, new FrameLayout(this)), texts, found);
        } catch (RuntimeException e) {
            Logger.log("Reading notification layout failed: " + e);
        }
        return found != null ? found[0] : null;
    }

    private static void walk(View v, List<String> texts, Bitmap[] icon) {
        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null && cs.length() > 0 && !texts.contains(cs.toString())) texts.add(cs.toString());
        } else if (v instanceof ImageView && icon != null && icon[0] == null) {
            Drawable d = ((ImageView) v).getDrawable();
            if (d != null && d.getIntrinsicWidth() > 0) icon[0] = IconMemory.render(d);
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), texts, icon);
        }
    }

    /** Logs the whole notification once per navigation session, to calibrate the parser. */
    private void dumpExtras(String app, Notification n) {
        StringBuilder sb = new StringBuilder(app).append(" notification dump: category=").append(n.category)
                .append(" flags=0x").append(Integer.toHexString(n.flags))
                .append(" customView=").append(n.contentView != null || n.bigContentView != null)
                .append(" smallIcon=").append(iconDescription(n.getSmallIcon()))
                .append(" largeIcon=").append(iconDescription(n.getLargeIcon()));
        for (String k : n.extras.keySet()) {
            Object v = n.extras.get(k);
            String s = String.valueOf(v);
            if (s.length() > 120) s = s.substring(0, 120) + "…";
            sb.append("\n    ").append(k).append(" = ").append(s);
        }
        Logger.log(sb.toString());
    }

    /** "bitmap", "resource com.calimoto.calimoto/ic_turn_left", for the dump. */
    private String iconDescription(Icon icon) {
        if (icon == null) return "none";
        int type = icon.getType();
        if (type == Icon.TYPE_BITMAP || type == Icon.TYPE_ADAPTIVE_BITMAP) return "bitmap";
        if (type != Icon.TYPE_RESOURCE) return "type " + type;
        String name = CalimotoIcons.resourceName(this, icon);
        return "resource " + icon.getResPackage() + "/" + (name != null ? name : Integer.toHexString(icon.getResId()));
    }
}
