package plugin.test.com.bodyGuard.storage;

/** Persisted per-player display choices. Server-wide disabled features stay disabled. */
public record PlayerSettings(Notification notification, Toggle guiSound,
                             Toggle actionBar, ListSort listSort) {
    public enum Notification { INHERIT, TITLE, CHAT }
    public enum Toggle { INHERIT, ON, OFF }
    public enum ListSort { STANDARD, DISTANCE, HEALTH_RATIO }

    public static final PlayerSettings DEFAULT = new PlayerSettings(
            Notification.INHERIT, Toggle.INHERIT, Toggle.INHERIT, ListSort.STANDARD);

    public PlayerSettings {
        if (notification == null || guiSound == null || actionBar == null || listSort == null) {
            throw new IllegalArgumentException("Player settings must be complete");
        }
    }

    public PlayerSettings nextNotification() {
        return new PlayerSettings(Notification.values()[(notification.ordinal() + 1)
                % Notification.values().length], guiSound, actionBar, listSort);
    }

    public PlayerSettings nextGuiSound() {
        return new PlayerSettings(notification, Toggle.values()[(guiSound.ordinal() + 1)
                % Toggle.values().length], actionBar, listSort);
    }

    public PlayerSettings nextActionBar() {
        return new PlayerSettings(notification, guiSound, Toggle.values()[(actionBar.ordinal() + 1)
                % Toggle.values().length], listSort);
    }

    public PlayerSettings nextListSort() {
        return new PlayerSettings(notification, guiSound, actionBar,
                ListSort.values()[(listSort.ordinal() + 1) % ListSort.values().length]);
    }
}
