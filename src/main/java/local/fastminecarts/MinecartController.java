package local.fastminecarts;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Powerable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class MinecartController implements Listener {
    private static final double MIN_VELOCITY_SQUARED = 1.0E-8;
    private static final int POST_LIMIT_STRAIGHT_BLOCKS = 10;
    private final JavaPlugin plugin;
    private final MinecartSettings settings;
    private final RailGeometry geometry;
    private final Set<UUID> geometryLimitedMinecarts = new HashSet<>();

    MinecartController(JavaPlugin plugin, MinecartSettings settings, RailGeometry geometry) {
        this.plugin = plugin;
        this.settings = settings;
        this.geometry = geometry;
    }

    @EventHandler
    public void onEntityAddedToWorld(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Minecart minecart) applyForPassengers(minecart);
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getVehicle() instanceof Minecart minecart && event.getEntered() instanceof Player) {
            applySettings(minecart);
        }
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getVehicle() instanceof Minecart minecart && event.getExited() instanceof Player) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!hasPlayerPassenger(minecart)) restoreVanillaSettings(minecart);
            });
        }
    }

    @EventHandler
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Minecart minecart)) return;
        if (!hasPlayerPassenger(minecart)) {
            restoreVanillaSettings(minecart);
            return;
        }
        applySettings(minecart);
        if (geometry.isOnIncline(event)) {
            geometryLimitedMinecarts.add(minecart.getUniqueId());
            minecart.setMaxSpeed(MinecartSettings.VANILLA_MAX_SPEED_PER_TICK);
            return;
        }

        Vector velocity = minecart.getVelocity();
        double speedSquared = velocity.lengthSquared();
        if (!Double.isFinite(speedSquared) || speedSquared < MIN_VELOCITY_SQUARED) return;
        BlockFace direction = geometry.primaryTravelFace(velocity);
        if (direction == null) return;

        double limit = geometry.findCurveSpeedLimit(event, direction, speedSquared);
        if (applyGeometryLimit(minecart, velocity, speedSquared, limit, false)) return;
        limit = geometry.findVanillaRailSpeedLimit(event, direction, speedSquared);
        if (applyGeometryLimit(minecart, velocity, speedSquared, limit, false)) return;
        limit = geometry.findInclineSpeedLimit(event, direction, speedSquared);
        if (applyGeometryLimit(minecart, velocity, speedSquared, limit, true)) return;

        if (settings.maxSpeedPerTick() <= 0.0 || settings.accelerationPerTick() <= 0.0) return;
        boolean boost = isBoostLocation(event.getTo()) || isBoostLocation(event.getFrom());
        boolean postLimit = geometryLimitedMinecarts.contains(minecart.getUniqueId())
                && geometry.hasStraightRailAhead(event, velocity, POST_LIMIT_STRAIGHT_BLOCKS);
        if (!boost && !postLimit) {
            if (geometry.isOffRail(event)) geometryLimitedMinecarts.remove(minecart.getUniqueId());
            return;
        }
        if (speedSquared >= settings.maxSpeedPerTickSquared()) {
            geometryLimitedMinecarts.remove(minecart.getUniqueId());
            return;
        }
        double currentSpeed = Math.sqrt(speedSquared);
        double newSpeed = Math.min(settings.maxSpeedPerTick(), currentSpeed + settings.accelerationPerTick());
        if (newSpeed <= currentSpeed) return;
        scaleVelocity(minecart, velocity, newSpeed / currentSpeed);
        if (newSpeed >= settings.maxSpeedPerTick()) geometryLimitedMinecarts.remove(minecart.getUniqueId());
    }

    private boolean applyGeometryLimit(Minecart minecart, Vector velocity, double speedSquared,
                                       double limit, boolean restoreVanillaMaximum) {
        if (limit < 0.0) return false;
        geometryLimitedMinecarts.add(minecart.getUniqueId());
        if (restoreVanillaMaximum) minecart.setMaxSpeed(MinecartSettings.VANILLA_MAX_SPEED_PER_TICK);
        if (speedSquared > limit * limit) limitVelocity(minecart, velocity, speedSquared, limit);
        return true;
    }

    @EventHandler
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (event.getVehicle() instanceof Minecart minecart) geometryLimitedMinecarts.remove(minecart.getUniqueId());
    }

    @EventHandler public void onBlockPlace(BlockPlaceEvent event) { invalidateIfRail(event.getBlock()); }
    @EventHandler public void onBlockBreak(BlockBreakEvent event) { invalidateIfRail(event.getBlock()); }
    @EventHandler public void onBlockPhysics(BlockPhysicsEvent event) { invalidateIfRail(event.getBlock()); }

    int applyToAllLoadedMinecarts() {
        int changed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Minecart minecart : world.getEntitiesByClass(Minecart.class)) {
                if (applyForPassengers(minecart)) changed++;
            }
        }
        return changed;
    }

    private boolean applyForPassengers(Minecart minecart) {
        if (hasPlayerPassenger(minecart)) return applySettings(minecart);
        geometryLimitedMinecarts.remove(minecart.getUniqueId());
        return restoreVanillaSettings(minecart);
    }

    private boolean applySettings(Minecart minecart) {
        boolean changed = false;
        if (Math.abs(minecart.getMaxSpeed() - settings.maxSpeedPerTick()) > 1.0E-9) {
            minecart.setMaxSpeed(settings.maxSpeedPerTick());
            changed = true;
        }
        if (minecart.isSlowWhenEmpty() != settings.slowWhenEmpty()) {
            minecart.setSlowWhenEmpty(settings.slowWhenEmpty());
            changed = true;
        }
        return changed;
    }

    private boolean restoreVanillaSettings(Minecart minecart) {
        boolean changed = false;
        if (Math.abs(minecart.getMaxSpeed() - MinecartSettings.VANILLA_MAX_SPEED_PER_TICK) > 1.0E-9) {
            minecart.setMaxSpeed(MinecartSettings.VANILLA_MAX_SPEED_PER_TICK);
            changed = true;
        }
        if (!minecart.isSlowWhenEmpty()) {
            minecart.setSlowWhenEmpty(true);
            changed = true;
        }
        geometryLimitedMinecarts.remove(minecart.getUniqueId());
        return changed;
    }

    private boolean hasPlayerPassenger(Minecart minecart) {
        for (Entity passenger : minecart.getPassengers()) if (passenger instanceof Player) return true;
        return false;
    }

    private boolean isBoostLocation(org.bukkit.Location location) {
        Block rail = geometry.findAssociatedRail(location);
        if (rail == null) return false;
        Material material = rail.getType();
        if (material == Material.POWERED_RAIL) {
            return rail.getBlockData() instanceof Powerable powerable && powerable.isPowered();
        }
        return settings.boostOnAllRails() && Tag.RAILS.isTagged(material);
    }

    private void invalidateIfRail(Block block) {
        if (geometry.isRail(block)) geometry.invalidateNear(block);
    }

    private void limitVelocity(Minecart minecart, Vector velocity, double speedSquared, double target) {
        if (target <= 0.0) {
            minecart.setVelocity(new Vector(0.0, 0.0, 0.0));
            return;
        }
        double current = Math.sqrt(speedSquared);
        if (current > target) scaleVelocity(minecart, velocity, target / current);
    }

    private void scaleVelocity(Minecart minecart, Vector velocity, double multiplier) {
        velocity.multiply(multiplier);
        minecart.setVelocity(velocity);
    }
}
