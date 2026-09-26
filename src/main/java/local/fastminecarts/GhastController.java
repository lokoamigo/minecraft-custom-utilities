package local.fastminecarts;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Ghast;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

final class GhastController implements Listener {
    private static final double TICKS_PER_SECOND = 20.0;
    private static final double DEFAULT_SPEED_BPS = 20.0;
    private static final double DEFAULT_ACCELERATION_BPS2 = 2.0;
    private static final double MIN_MOVING_SPEED_PER_TICK = 0.01;
    private static final double COLLISION_SPEED_RATIO = 0.35;

    private final Map<UUID, GhastState> ghasts = new HashMap<>();
    private final double maximumSpeedPerTick;
    private final double accelerationPerTick;

    GhastController(FastMinecartsPlugin plugin) {
        plugin.getConfig().addDefault("ghast-speed-blocks-per-second", DEFAULT_SPEED_BPS);
        plugin.getConfig().addDefault("ghast-acceleration-blocks-per-second-squared", DEFAULT_ACCELERATION_BPS2);
        plugin.getConfig().options().copyDefaults(true);

        double speed = positiveSetting(plugin, "ghast-speed-blocks-per-second", DEFAULT_SPEED_BPS);
        double acceleration = positiveSetting(plugin,
                "ghast-acceleration-blocks-per-second-squared", DEFAULT_ACCELERATION_BPS2);
        maximumSpeedPerTick = speed / TICKS_PER_SECOND;
        accelerationPerTick = acceleration / (TICKS_PER_SECOND * TICKS_PER_SECOND);

        plugin.getConfig().set("ghast-speed-blocks-per-second", speed);
        plugin.getConfig().set("ghast-acceleration-blocks-per-second-squared", acceleration);
        plugin.saveConfig();

        for (World world : Bukkit.getWorlds()) {
            world.getEntitiesByClass(Ghast.class).forEach(this::track);
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    int trackedGhasts() {
        return ghasts.size();
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpawn(EntitySpawnEvent event) {
        if (event.getEntity() instanceof Ghast ghast) {
            track(ghast);
        }
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Ghast ghast) {
                track(ghast);
            }
        }
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Ghast) {
                ghasts.remove(entity.getUniqueId());
            }
        }
    }

    private void track(Ghast ghast) {
        ghasts.put(ghast.getUniqueId(), new GhastState(ghast));
    }

    private void tick() {
        Iterator<GhastState> iterator = ghasts.values().iterator();
        while (iterator.hasNext()) {
            GhastState state = iterator.next();
            Ghast ghast = state.ghast;
            if (!ghast.isValid() || ghast.isDead()) {
                iterator.remove();
                continue;
            }

            Vector velocity = ghast.getVelocity();
            double currentSpeed = velocity.length();
            if (!Double.isFinite(currentSpeed) || currentSpeed < MIN_MOVING_SPEED_PER_TICK) {
                state.rampSpeedPerTick = 0.0;
                continue;
            }

            if (state.rampSpeedPerTick == 0.0
                    || currentSpeed < state.rampSpeedPerTick * COLLISION_SPEED_RATIO) {
                state.rampSpeedPerTick = currentSpeed;
            }

            double nextSpeed = Math.min(maximumSpeedPerTick,
                    Math.max(currentSpeed, state.rampSpeedPerTick) + accelerationPerTick);
            state.rampSpeedPerTick = nextSpeed;
            ghast.setVelocity(velocity.multiply(nextSpeed / currentSpeed));
        }
    }

    private static double positiveSetting(FastMinecartsPlugin plugin, String key, double fallback) {
        double value = plugin.getConfig().getDouble(key, fallback);
        if (Double.isFinite(value) && value > 0.0) {
            return value;
        }
        plugin.getLogger().warning("Invalid " + key + " in config.yml; using " + fallback + ".");
        return fallback;
    }

    private static final class GhastState {
        private final Ghast ghast;
        private double rampSpeedPerTick;

        private GhastState(Ghast ghast) {
            this.ghast = ghast;
        }
    }
}
