package androidx.media3.mpvplayer;

import android.view.KeyEvent;

public final class MpvDiscMenuPolicy {

    private MpvDiscMenuPolicy() {
    }

    public static boolean usesRawIso(String uri) {
        return uri != null && uri.startsWith("webhtv-dvdiso://") && uri.endsWith("/raw");
    }

    public static boolean hasSinglePlaybackTimeline(String uri, boolean fileLoaded,
                                                     boolean navigationActive) {
        // Wait for libbluray to distinguish HDMV from the ordinary-title fallback.
        // A navigation session can change playlists even while no menu is visible.
        return !usesRawIso(uri) || (fileLoaded && !navigationActive);
    }

    public static boolean isTerminalEof(boolean eofReached, boolean navigationActive) {
        // A menu background/clip can reach EOF while libbluray still owns the
        // session and awaits input or switches playlists. Only end-file ends
        // that session; ordinary media retain their keep-open EOF behavior.
        return eofReached && !navigationActive;
    }

    public static String isoUri(String uri, boolean enabled) {
        if (!enabled || uri == null || !uri.startsWith("webhtv-dvdiso://")
                || !uri.endsWith("/longest")) return uri;
        return uri.substring(0, uri.length() - "/longest".length()) + "/raw";
    }

    public static String keyAction(int keyCode) {
        return switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP -> "up";
            case KeyEvent.KEYCODE_DPAD_DOWN -> "down";
            case KeyEvent.KEYCODE_DPAD_LEFT -> "left";
            case KeyEvent.KEYCODE_DPAD_RIGHT -> "right";
            case KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> "select";
            case KeyEvent.KEYCODE_BACK -> "prev";
            case KeyEvent.KEYCODE_MENU -> "popup";
            default -> null;
        };
    }
}
