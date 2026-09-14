package plugin.test.com.bodyGuard.guard;

import java.util.Locale;

public enum GuardMode {
    FOLLOW,
    STAY,
    GUARD;

    public static GuardMode fromString(String value) {
        if (value == null) {
            return null;
        }
        try {
            return value.trim().toUpperCase(Locale.ROOT).equals("FOLLOW")
                    ? FOLLOW
                    : value.trim().toUpperCase(Locale.ROOT).equals("STAY")
                    ? STAY
                    : value.trim().toUpperCase(Locale.ROOT).equals("GUARD") ? GUARD : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public String commandName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        return switch (this) {
            case FOLLOW -> "follow（追従）";
            case STAY -> "stay（待機）";
            case GUARD -> "guard（警備）";
        };
    }

    /** Short Japanese label used by the player-facing inventory UI and list. */
    public String japaneseName() {
        return switch (this) {
            case FOLLOW -> "追従";
            case STAY -> "待機";
            case GUARD -> "警備";
        };
    }
}
