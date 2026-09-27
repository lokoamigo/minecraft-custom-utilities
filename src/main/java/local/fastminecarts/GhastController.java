package local.fastminecarts;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

final class GhastController implements Listener {
    private static final double TICKS_PER_SECOND = 20.0;
    static final double MAX_ALLOWED_SPEED_BPS = 200.0;
    static final double MAX_ALLOWED_ACCELERATION_BPS2 = 100.0;
    private static final double DEFAULT_SPEED_BPS = 200.0;
    private static final double DEFAULT_ACCELERATION_BPS2 = 1.0;
    private static final double VANILLA_RIDDEN_SPEED_BPS = 4.0;

    private final FastMinecartsPlugin plugin;
    private final Map<UUID, GhastState> ghasts = new HashMap<>();
    private double speedBlocksPerSecond;
    private double accelerationBlocksPerSecondSquared;

    GhastController(FastMinecartsPlugin plugin) {
        this.plugin = plugin;
        plugin.getConfig().addDefault("ghast-speed-blocks-per-second", DEFAULT_SPEED_BPS);
        plugin.getConfig().addDefault("ghast-acceleration-blocks-per-second-squared", DEFAULT_ACCELERATION_BPS2);
        plugin.getConfig().options().copyDefaults(true);

        speedBlocksPerSecond = positiveSetting(plugin, "ghast-speed-blocks-per-second",
                DEFAULT_SPEED_BPS, MAX_ALLOWED_SPEED_BPS);
        accelerationBlocksPerSecondSquared = positiveSetting(plugin,
                "ghast-acceleration-blocks-per-second-squared", DEFAULT_ACCELERATION_BPS2);
        plugin.getConfig().set("ghast-speed-blocks-per-second", speedBlocksPerSecond);
        plugin.getConfig().set("ghast-acceleration-blocks-per-second-squared", accelerationBlocksPerSecondSquared);
        plugin.saveConfig();

        for (World world : Bukkit.getWorlds()) {
            world.getEntitiesByClass(HappyGhast.class).forEach(this::track);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    int trackedGhasts() {
        return ghasts.size();
    }

    double speedBlocksPerSecond() {
        return speedBlocksPerSecond;
    }

    double accelerationBlocksPerSecondSquared() {
        return accelerationBlocksPerSecondSquared;
    }

    void setSpeedBlocksPerSecond(double value) {
        speedBlocksPerSecond = value;
        resetRamps();
        plugin.getConfig().set("ghast-speed-blocks-per-second", value);
        plugin.saveConfig();
    }

    void setAccelerationBlocksPerSecondSquared(double value) {
        accelerationBlocksPerSecondSquared = value;
        resetRamps();
        plugin.getConfig().set("ghast-acceleration-blocks-per-second-squared", value);
        plugin.saveConfig();
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof HappyGhast ghast) {
            track(ghast);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof HappyGhast ghast) {
                track(ghast);
            }
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof HappyGhast) {
                GhastState state = ghasts.remove(entity.getUniqueId());
                if (state != null) {
                    state.restoreVanillaSpeed();
                }
            }
        }
    }

    void shutdown() {
        ghasts.values().forEach(GhastState::restoreVanillaSpeed);
        ghasts.clear();
    }

    private void track(HappyGhast ghast) {
        ghasts.computeIfAbsent(ghast.getUniqueId(), ignored -> new GhastState(ghast));
    }

    private void tick() {
        discoverPlayerLoadedMounts();
        Iterator<GhastState> iterator = ghasts.values().iterator();
        while (iterator.hasNext()) {
            GhastState state = iterator.next();
            HappyGhast ghast = state.ghast;
            if (!ghast.isValid() || ghast.isDead()) {
                state.restoreVanillaSpeed();
                iterator.remove();
                continue;
            }
            Player rider = playerRider(ghast);
            if (rider == null || !hasMovementInput(rider)) {
                state.rampSpeedBlocksPerSecond = 0.0;
                state.restoreVanillaSpeed();
                continue;
            }

            double currentRamp = state.rampSpeedBlocksPerSecond == 0.0
                    ? VANILLA_RIDDEN_SPEED_BPS : state.rampSpeedBlocksPerSecond;
            state.rampSpeedBlocksPerSecond = Math.min(speedBlocksPerSecond,
                    currentRamp + accelerationBlocksPerSecondSquared / TICKS_PER_SECOND);
            state.applyRiddenSpeed(state.rampSpeedBlocksPerSecond);
        }
    }

    private void discoverPlayerLoadedMounts() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            for (Entity entity : player.getWorld().getNearbyEntities(
                    player.getLocation(), 1.0, 1.0, 1.0,
                    candidate -> candidate instanceof HappyGhast)) {
                track((HappyGhast) entity);
            }
        }
    }

    private void resetRamps() {
        ghasts.values().forEach(state -> {
            state.rampSpeedBlocksPerSecond = 0.0;
            state.restoreVanillaSpeed();
        });
    }

    private static double positiveSetting(FastMinecartsPlugin plugin, String key, double fallback) {
        return positiveSetting(plugin, key, fallback, MAX_ALLOWED_ACCELERATION_BPS2);
    }

    private static double positiveSetting(FastMinecartsPlugin plugin, String key, double fallback, double maximum) {
        double value = plugin.getConfig().getDouble(key, fallback);
        if (Double.isFinite(value) && value > 0.0 && value <= maximum) {
            return value;
        }
        plugin.getLogger().warning("Invalid " + key + " in config.yml; expected a value above 0 and at most "
                + maximum + "; using " + fallback + ".");
        return fallback;
    }

    private static Player playerRider(HappyGhast ghast) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Entity vehicle = player.getVehicle();
            while (vehicle != null) {
                if (vehicle.getUniqueId().equals(ghast.getUniqueId())) {
                    return player;
                }
                vehicle = vehicle.getVehicle();
            }
            // Happy Ghast attachments in Paper 26.3 are stored in RootVehicle NBT,
            // but are not exposed by Bukkit's passenger/vehicle methods. Attached
            // players share the mount's center position, so use that as a narrow
            // fallback until the API exposes the attachment directly.
            if (player.getWorld().equals(ghast.getWorld())
                    && player.getLocation().distanceSquared(ghast.getLocation()) < 1.0) {
                return player;
            }
        }
        return null;
    }

    private static boolean hasMovementInput(Player player) {
        return player.getCurrentInput().isForward()
                || player.getCurrentInput().isBackward()
                || player.getCurrentInput().isLeft()
                || player.getCurrentInput().isRight()
                || player.getCurrentInput().isJump()
                || player.getCurrentInput().isSneak();
    }

    private static final class GhastState {
        private final HappyGhast ghast;
        private final double vanillaFlyingSpeed;
        private double rampSpeedBlocksPerSecond;

        private GhastState(HappyGhast ghast) {
            this.ghast = ghast;
            AttributeInstance flyingSpeed = ghast.getAttribute(Attribute.FLYING_SPEED);
            this.vanillaFlyingSpeed = flyingSpeed == null ? 0.05 : flyingSpeed.getBaseValue();
        }

        private void applyRiddenSpeed(double speedBlocksPerSecond) {
            AttributeInstance flyingSpeed = ghast.getAttribute(Attribute.FLYING_SPEED);
            if (flyingSpeed != null) {
                double multiplier = Math.sqrt(speedBlocksPerSecond / VANILLA_RIDDEN_SPEED_BPS);
                flyingSpeed.setBaseValue(vanillaFlyingSpeed * multiplier);
            }
        }

        private void restoreVanillaSpeed() {
            AttributeInstance flyingSpeed = ghast.getAttribute(Attribute.FLYING_SPEED);
            if (flyingSpeed != null && flyingSpeed.getBaseValue() != vanillaFlyingSpeed) {
                flyingSpeed.setBaseValue(vanillaFlyingSpeed);
            }
        }
    }
}
