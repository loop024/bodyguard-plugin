package plugin.test.com.bodyGuard.storage;

/** Supported on-disk format versions, shared by readers and writers. */
public final class StorageSchema {
    public static final int GUARDS = 6;
    public static final int PLAYERS = 3;
    public static final int OPERATIONS = 1;

    private StorageSchema() {
    }

    public static int supportedVersion(String root) {
        return switch (root) {
            case "guards" -> GUARDS;
            case "players" -> PLAYERS;
            case "operations" -> OPERATIONS;
            default -> Integer.MAX_VALUE;
        };
    }
}
