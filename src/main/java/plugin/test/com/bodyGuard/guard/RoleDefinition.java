package plugin.test.com.bodyGuard.guard;

/** One configured permission-node based protection role. */
public record RoleDefinition(String id, String permission, int priority) {
}
