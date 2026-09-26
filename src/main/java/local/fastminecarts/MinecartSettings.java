package local.fastminecarts;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

final class MinecartSettings {
    static final double TICKS_PER_SECOND = 20.0;
    static final double VANILLA_MAX_SPEED_PER_TICK = 8.0 / TICKS_PER_SECOND;
    static final double MAX_ALLOWED_SPEED_BPS = 1000.0;
    static final double MAX_ALLOWED_ACCELERATION_BPS2 = 10000.0;

    private static final double DEFAULT_SPEED_BPS = 8.0;
    private static final double DEFAULT_ACCELERATION_BPS2 = 0.0;
    private static final double DEFAULT_CURVE_SPEED_BPS = 8.0;

    private final JavaPlugin plugin;
    private double speedBlocksPerSecond;
    private double accelerationBlocksPerSecondSquared;
    private double curveSpeedBlocksPerSecond;
    private boolean slowWhenEmpty;
    private boolean boostOnAllRails;
    private double maxSpeedPerTick;
    private double maxSpeedPerTickSquared;
    private double accelerationPerTick;
    private double curveSpeedPerTick;

    MinecartSettings(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    void installDefaults() {
        FileConfiguration config = plugin.getConfig();
        config.addDefault("speed-blocks-per-second", DEFAULT_SPEED_BPS);
        config.addDefault("acceleration-blocks-per-second-squared", DEFAULT_ACCELERATION_BPS2);
        config.addDefault("curve-speed-blocks-per-second", DEFAULT_CURVE_SPEED_BPS);
        config.addDefault("slow-when-empty", true);
        config.addDefault("boost-on-all-rails", false);
        config.options().copyDefaults(true);
    }

    void load() {
        FileConfiguration config = plugin.getConfig();
        speedBlocksPerSecond = sanitize("speed-blocks-per-second",
                config.getDouble("speed-blocks-per-second", DEFAULT_SPEED_BPS),
                DEFAULT_SPEED_BPS, MAX_ALLOWED_SPEED_BPS);
        accelerationBlocksPerSecondSquared = sanitize(
                "acceleration-blocks-per-second-squared",
                config.getDouble("acceleration-blocks-per-second-squared", DEFAULT_ACCELERATION_BPS2),
                DEFAULT_ACCELERATION_BPS2, MAX_ALLOWED_ACCELERATION_BPS2);
        curveSpeedBlocksPerSecond = sanitize("curve-speed-blocks-per-second",
                config.getDouble("curve-speed-blocks-per-second", DEFAULT_CURVE_SPEED_BPS),
                DEFAULT_CURVE_SPEED_BPS, MAX_ALLOWED_SPEED_BPS);
        slowWhenEmpty = config.getBoolean("slow-when-empty", true);
        boostOnAllRails = config.getBoolean("boost-on-all-rails", false);
        recalculate();

        config.set("speed-blocks-per-second", speedBlocksPerSecond);
        config.set("acceleration-blocks-per-second-squared", accelerationBlocksPerSecondSquared);
        config.set("curve-speed-blocks-per-second", curveSpeedBlocksPerSecond);
        config.set("incline-speed-blocks-per-second", null);
        config.set("slow-when-empty", slowWhenEmpty);
        config.set("boost-on-all-rails", boostOnAllRails);
        plugin.saveConfig();
    }

    void reload() {
        plugin.reloadConfig();
        installDefaults();
        load();
    }

    void setSpeedBlocksPerSecond(double value) {
        speedBlocksPerSecond = value;
        recalculate();
        plugin.getConfig().set("speed-blocks-per-second", value);
        plugin.saveConfig();
    }

    void setAccelerationBlocksPerSecondSquared(double value) {
        accelerationBlocksPerSecondSquared = value;
        recalculate();
        plugin.getConfig().set("acceleration-blocks-per-second-squared", value);
        plugin.saveConfig();
    }

    void setCurveSpeedBlocksPerSecond(double value) {
        curveSpeedBlocksPerSecond = value;
        recalculate();
        plugin.getConfig().set("curve-speed-blocks-per-second", value);
        plugin.saveConfig();
    }

    private void recalculate() {
        maxSpeedPerTick = speedBlocksPerSecond / TICKS_PER_SECOND;
        maxSpeedPerTickSquared = maxSpeedPerTick * maxSpeedPerTick;
        accelerationPerTick = accelerationBlocksPerSecondSquared
                / (TICKS_PER_SECOND * TICKS_PER_SECOND);
        curveSpeedPerTick = curveSpeedBlocksPerSecond / TICKS_PER_SECOND;
    }

    private double sanitize(String key, double value, double defaultValue, double maximum) {
        if (!Double.isFinite(value) || value < 0.0) {
            plugin.getLogger().warning(
                    "Invalid " + key + " in config.yml; using " + defaultValue + ".");
            return defaultValue;
        }
        if (value > maximum) {
            plugin.getLogger().warning(
                    key + " is above the allowed maximum of " + maximum + "; clamping it.");
            return maximum;
        }
        return value;
    }

    double speedBlocksPerSecond() { return speedBlocksPerSecond; }
    double accelerationBlocksPerSecondSquared() { return accelerationBlocksPerSecondSquared; }
    double curveSpeedBlocksPerSecond() { return curveSpeedBlocksPerSecond; }
    boolean slowWhenEmpty() { return slowWhenEmpty; }
    boolean boostOnAllRails() { return boostOnAllRails; }
    double maxSpeedPerTick() { return maxSpeedPerTick; }
    double maxSpeedPerTickSquared() { return maxSpeedPerTickSquared; }
    double accelerationPerTick() { return accelerationPerTick; }
    double curveSpeedPerTick() { return curveSpeedPerTick; }
}
