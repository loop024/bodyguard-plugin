package plugin.test.com.bodyGuard.guard;

public enum CombatPolicy {
    LEGACY("従来どおり"), RETALIATE("反撃のみ"), ASSIST("攻撃を支援"), INTERCEPT("周辺の敵を迎撃"), PASSIVE("戦闘しない");
    private final String label;
    CombatPolicy(String label) { this.label = label; }
    public String label() { return label; }
    public String commandName() { return name().toLowerCase(java.util.Locale.ROOT); }
    public CombatPolicy next() { return values()[(ordinal() + 1) % values().length]; }
    public static CombatPolicy parse(String value) {
        try { return valueOf(value.toUpperCase(java.util.Locale.ROOT)); }
        catch (RuntimeException invalid) { return null; }
    }
    public boolean allowsCommand(boolean defense) {
        return this != PASSIVE && (defense || this != RETALIATE);
    }
}
