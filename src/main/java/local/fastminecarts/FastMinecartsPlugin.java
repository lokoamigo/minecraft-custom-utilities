package local.fastminecarts;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Powerable;
import org.bukkit.block.data.Rail;
import org.bukkit.command.CommandSender;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class FastMinecartsPlugin extends JavaPlugin implements Listener {

    private static final double TICKS_PER_SECOND = 20.0;

    private static final double DEFAULT_SPEED_BPS = 8.0;
    private static final double DEFAULT_ACCELERATION_BPS2 = 0.0;
    private static final double DEFAULT_CURVE_SPEED_BPS = 8.0;
    private static final double VANILLA_MAX_SPEED_PER_TICK =
            DEFAULT_SPEED_BPS / TICKS_PER_SECOND;

    private static final double MAX_ALLOWED_SPEED_BPS = 1000.0;
    private static final double MAX_ALLOWED_ACCELERATION_BPS2 = 10000.0;

    private static final int CURVE_LOOKAHEAD_TICKS = 3;
    private static final int CURVE_LOOKAHEAD_EXTRA_BLOCKS = 2;
    private static final int MAX_CURVE_LOOKAHEAD_BLOCKS = 64;
    private static final int POST_LIMIT_STRAIGHT_BLOCKS = 10;
    private static final int GEOMETRY_CACHE_MAX_ENTRIES = 4096;
    private static final long GEOMETRY_CACHE_TTL_MILLIS = 5000L;

    /*
     * Prevents attempting to normalize a velocity vector that is effectively zero.
     * Vanilla powered-rail physics will handle initially starting a stationary cart.
     */
    private static final double MIN_VELOCITY_SQUARED = 1.0E-8;

    private static final String ADMIN_PERMISSION = "fastminecarts.admin";

    /*
     * User-facing configuration values.
     */
    private double speedBlocksPerSecond;
    private double accelerationBlocksPerSecondSquared;
    private double curveSpeedBlocksPerSecond;

    private boolean slowWhenEmpty;
    private boolean boostOnAllRails;

    /*
     * Precomputed values used by the movement event.
     *
     * These are calculated only when configuration changes instead of doing
     * divisions and multiplications for every minecart movement.
     */
    private double maxSpeedPerTick;
    private double maxSpeedPerTickSquared;
    private double accelerationPerTick;
    private double curveSpeedPerTick;

    private final Set<UUID> geometryLimitedMinecarts = new HashSet<>();
    private final Map<GeometryCacheKey, GeometryCacheEntry> geometryCache =
            new LinkedHashMap<>(256, 0.75F, true) {

                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<GeometryCacheKey, GeometryCacheEntry> eldest
                ) {
                    return size() > GEOMETRY_CACHE_MAX_ENTRIES;
                }
            };

    private enum GeometryKind {
        CURVE,
        INCLINE,
        VANILLA_RAIL,
        STRAIGHT
    }

    private record GeometryCacheKey(
            UUID worldId,
            int x,
            int y,
            int z,
            BlockFace direction,
            GeometryKind kind
    ) {

        private static GeometryCacheKey from(
                Block block,
                BlockFace direction,
                GeometryKind kind
        ) {
            return new GeometryCacheKey(
                    block.getWorld().getUID(),
                    block.getX(),
                    block.getY(),
                    block.getZ(),
                    direction,
                    kind
            );
        }
    }

    private record GeometryCacheEntry(
            double speedLimit,
            int scannedBlocks,
            long expiresAtMillis
    ) {
    }

    @Override
    public void onEnable() {
        installConfigDefaults();
        loadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);

        registerCommand(
                "minecartspeed",
                "Configure FastMinecarts",
                new BasicCommand() {

                    @Override
                    public void execute(CommandSourceStack source, String[] args) {
                        handleCommand(source.getSender(), args);
                    }

                    @Override
                    public String permission() {
                        return ADMIN_PERMISSION;
                    }

                    @Override
                    public Collection<String> suggest(
                            CommandSourceStack source,
                            String[] args
                    ) {
                        return handleSuggestions(args);
                    }
                }
        );

        int changed = applyToAllLoadedMinecarts();

        getLogger().info(String.format(
                Locale.ROOT,
                "Enabled. Target speed: %.2f blocks/sec, acceleration: %.2f blocks/sec^2, curve speed: %.2f blocks/sec. Updated %d loaded minecart(s).",
                speedBlocksPerSecond,
                accelerationBlocksPerSecondSquared,
                curveSpeedBlocksPerSecond,
                changed
        ));

        if (speedBlocksPerSecond > 100.0) {
            getLogger().warning(
                    "Minecart speeds above 100 blocks/sec can cause chunk-loading, collision, "
                            + "or visual problems. Use extreme speeds carefully."
            );
        }
    }

    /*
     * -------------------------------------------------------------------------
     * Configuration
     * -------------------------------------------------------------------------
     */

    private void installConfigDefaults() {
        /*
         * These defaults are added programmatically, so an older config.yml
         * does not need to already contain the new settings.
         */
        getConfig().addDefault(
                "speed-blocks-per-second",
                DEFAULT_SPEED_BPS
        );

        getConfig().addDefault(
                "acceleration-blocks-per-second-squared",
                DEFAULT_ACCELERATION_BPS2
        );

        getConfig().addDefault(
                "curve-speed-blocks-per-second",
                DEFAULT_CURVE_SPEED_BPS
        );

        /*
         * false is recommended for high-speed railways because empty carts
         * otherwise receive Minecraft's additional empty-cart slowdown.
         */
        getConfig().addDefault(
                "slow-when-empty",
                true
        );

        /*
         * false:
         *   Only powered POWERED_RAIL blocks provide our extra acceleration.
         *
         * true:
         *   Normal rail, detector rail, and activator rail can also provide
         *   acceleration. Unpowered POWERED_RAIL blocks still act as brakes.
         */
        getConfig().addDefault(
                "boost-on-all-rails",
                false
        );

        getConfig().options().copyDefaults(true);
    }

    private void loadSettings() {
        speedBlocksPerSecond = sanitizeSetting(
                "speed-blocks-per-second",
                getConfig().getDouble(
                        "speed-blocks-per-second",
                        DEFAULT_SPEED_BPS
                ),
                DEFAULT_SPEED_BPS,
                MAX_ALLOWED_SPEED_BPS
        );

        accelerationBlocksPerSecondSquared = sanitizeSetting(
                "acceleration-blocks-per-second-squared",
                getConfig().getDouble(
                        "acceleration-blocks-per-second-squared",
                        DEFAULT_ACCELERATION_BPS2
                ),
                DEFAULT_ACCELERATION_BPS2,
                MAX_ALLOWED_ACCELERATION_BPS2
        );

        curveSpeedBlocksPerSecond = sanitizeSetting(
                "curve-speed-blocks-per-second",
                getConfig().getDouble(
                        "curve-speed-blocks-per-second",
                        DEFAULT_CURVE_SPEED_BPS
                ),
                DEFAULT_CURVE_SPEED_BPS,
                MAX_ALLOWED_SPEED_BPS
        );

        slowWhenEmpty = getConfig().getBoolean(
                "slow-when-empty",
                true
        );

        boostOnAllRails = getConfig().getBoolean(
                "boost-on-all-rails",
                false
        );

        /*
         * Minecart velocity is measured in blocks/tick.
         *
         * speed:
         *     blocks/sec / 20 = blocks/tick
         *
         * acceleration:
         *     blocks/sec^2 / 20 / 20
         *     = blocks/tick gained each tick
         */
        recalculateCachedSettings();

        /*
         * Write normalized values back to disk.
         *
         * This also means an invalid value such as -100 or 999999 does not
         * remain permanently in config.yml producing warnings every restart.
         */
        getConfig().set(
                "speed-blocks-per-second",
                speedBlocksPerSecond
        );

        getConfig().set(
                "acceleration-blocks-per-second-squared",
                accelerationBlocksPerSecondSquared
        );

        getConfig().set(
                "curve-speed-blocks-per-second",
                curveSpeedBlocksPerSecond
        );

        // Remove the retired setting from configs created by older versions.
        getConfig().set("incline-speed-blocks-per-second", null);

        getConfig().set(
                "slow-when-empty",
                slowWhenEmpty
        );

        getConfig().set(
                "boost-on-all-rails",
                boostOnAllRails
        );

        saveConfig();
    }

    private void recalculateCachedSettings() {
        maxSpeedPerTick =
                speedBlocksPerSecond / TICKS_PER_SECOND;

        maxSpeedPerTickSquared =
                maxSpeedPerTick * maxSpeedPerTick;

        accelerationPerTick =
                accelerationBlocksPerSecondSquared
                        / (TICKS_PER_SECOND * TICKS_PER_SECOND);

        curveSpeedPerTick =
                curveSpeedBlocksPerSecond / TICKS_PER_SECOND;

        geometryCache.clear();
    }

    private double sanitizeSetting(
            String key,
            double value,
            double defaultValue,
            double maximum
    ) {
        if (!Double.isFinite(value) || value < 0.0) {
            getLogger().warning(
                    "Invalid " + key + " in config.yml; using " + defaultValue + "."
            );

            return defaultValue;
        }

        if (value > maximum) {
            getLogger().warning(
                    key + " is above the allowed maximum of "
                            + maximum
                            + "; clamping it."
            );

            return maximum;
        }

        return value;
    }

    /*
     * -------------------------------------------------------------------------
     * Minecart setup
     * -------------------------------------------------------------------------
     */

    @EventHandler
    public void onEntityAddedToWorld(EntityAddToWorldEvent event) {
        if (event.getEntity() instanceof Minecart minecart) {
            applyMinecartSettingsForPassengers(minecart);
        }
    }

    @EventHandler
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getVehicle() instanceof Minecart minecart
                && event.getEntered() instanceof Player) {
            applyMinecartSettings(minecart);
        }
    }

    @EventHandler
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getVehicle() instanceof Minecart minecart
                && event.getExited() instanceof Player) {
            Bukkit.getScheduler().runTask(this, () -> {
                if (!hasPlayerPassenger(minecart)) {
                    restoreVanillaMinecartSettings(minecart);
                }
            });
        }
    }

    private boolean applyMinecartSettings(Minecart minecart) {
        boolean changed = false;

        /*
         * setMaxSpeed() is still important because Minecraft would otherwise
         * clamp the cart before our desired high speed can be reached.
         */
        if (Math.abs(minecart.getMaxSpeed() - maxSpeedPerTick) > 1.0E-9) {
            minecart.setMaxSpeed(maxSpeedPerTick);
            changed = true;
        }

        if (minecart.isSlowWhenEmpty() != slowWhenEmpty) {
            minecart.setSlowWhenEmpty(slowWhenEmpty);
            changed = true;
        }

        return changed;
    }

    private boolean applyMinecartSettingsForPassengers(Minecart minecart) {
        if (hasPlayerPassenger(minecart)) {
            return applyMinecartSettings(minecart);
        }

        geometryLimitedMinecarts.remove(minecart.getUniqueId());
        return restoreVanillaMinecartSettings(minecart);
    }

    private boolean restoreVanillaMinecartSettings(Minecart minecart) {
        boolean changed = false;

        if (Math.abs(minecart.getMaxSpeed() - VANILLA_MAX_SPEED_PER_TICK) > 1.0E-9) {
            minecart.setMaxSpeed(VANILLA_MAX_SPEED_PER_TICK);
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
        for (Entity passenger : minecart.getPassengers()) {
            if (passenger instanceof Player) {
                return true;
            }
        }

        return false;
    }

    private int applyToAllLoadedMinecarts() {
        int changed = 0;

        for (World world : Bukkit.getWorlds()) {
            /*
             * More efficient than world.getEntities() followed by checking
             * every entity on the server with instanceof.
             */
            for (Minecart minecart : world.getEntitiesByClass(Minecart.class)) {
                if (applyMinecartSettingsForPassengers(minecart)) {
                    changed++;
                }
            }
        }

        return changed;
    }

    /*
     * -------------------------------------------------------------------------
     * Actual high-speed acceleration and curve limiting
     * -------------------------------------------------------------------------
     */

    @EventHandler
    public void onVehicleMove(VehicleMoveEvent event) {
        if (!(event.getVehicle() instanceof Minecart minecart)) {
            return;
        }

        if (!hasPlayerPassenger(minecart)) {
            restoreVanillaMinecartSettings(minecart);
            return;
        }

        applyMinecartSettings(minecart);

        /*
         * Once a cart is on an ascending rail, stop touching its velocity.
         * In particular, this lets vanilla powered-rail physics launch and
         * accelerate carts uphill instead of having that change immediately
         * scaled away by the plugin's slope speed limiter.
         */
        if (isOnIncline(event)) {
            geometryLimitedMinecarts.add(minecart.getUniqueId());
            minecart.setMaxSpeed(VANILLA_MAX_SPEED_PER_TICK);
            return;
        }

        Vector velocity = minecart.getVelocity();

        double currentSpeedSquared = velocity.lengthSquared();

        /*
         * Defensive check. Bukkit should never normally give us an invalid
         * velocity, but don't let NaN/Infinity propagate into setVelocity().
         */
        if (!Double.isFinite(currentSpeedSquared)) {
            return;
        }

        /*
         * Do not normalize an effectively-zero vector.
         *
         * Vanilla Minecraft can start a stationary cart on a powered rail
         * when the normal powered-rail launch conditions are satisfied.
         * Once it has movement, this plugin accelerates it.
         */
        if (currentSpeedSquared < MIN_VELOCITY_SQUARED) {
            return;
        }

        BlockFace travelFace = getPrimaryTravelFace(velocity);

        if (travelFace == null) {
            return;
        }

        double curveSpeedLimit = findCurveSpeedLimit(
                event,
                travelFace,
                currentSpeedSquared
        );

        if (curveSpeedLimit >= 0.0) {
            geometryLimitedMinecarts.add(minecart.getUniqueId());

            if (currentSpeedSquared > curveSpeedLimit * curveSpeedLimit) {
                limitVelocity(minecart, velocity, currentSpeedSquared, curveSpeedLimit);
            }

            return;
        }

        double vanillaRailSpeedLimit = findVanillaRailSpeedLimit(
                event,
                travelFace,
                currentSpeedSquared
        );

        if (vanillaRailSpeedLimit >= 0.0) {
            geometryLimitedMinecarts.add(minecart.getUniqueId());

            if (currentSpeedSquared > vanillaRailSpeedLimit * vanillaRailSpeedLimit) {
                limitVelocity(
                        minecart,
                        velocity,
                        currentSpeedSquared,
                        vanillaRailSpeedLimit
                );
            }

            return;
        }

        double inclineSpeedLimit = findInclineSpeedLimitAhead(
                event,
                travelFace,
                currentSpeedSquared
        );

        if (inclineSpeedLimit >= 0.0) {
            geometryLimitedMinecarts.add(minecart.getUniqueId());

            // Brake before the slope; on the slope itself vanilla takes over.
            minecart.setMaxSpeed(VANILLA_MAX_SPEED_PER_TICK);

            if (currentSpeedSquared > inclineSpeedLimit * inclineSpeedLimit) {
                limitVelocity(minecart, velocity, currentSpeedSquared, inclineSpeedLimit);
            }

            return;
        }

        /*
         * Nothing useful to do when either target speed or acceleration is 0.
         */
        if (maxSpeedPerTick <= 0.0 || accelerationPerTick <= 0.0) {
            return;
        }

        /*
         * Check both ends of this movement.
         *
         * Checking the previous position as well helps at higher speeds where
         * the minecart may already have moved beyond the powered rail by the
         * time this event is processed.
         */
        boolean boostLocation = isBoostLocation(event.getTo())
                || isBoostLocation(event.getFrom());

        boolean postLimitStraightaway =
                geometryLimitedMinecarts.contains(minecart.getUniqueId())
                        && hasStraightRailAhead(event, velocity, POST_LIMIT_STRAIGHT_BLOCKS);

        if (!boostLocation && !postLimitStraightaway) {
            clearGeometryLimitedStateIfOffRail(minecart, event);
            return;
        }

        /*
         * Already at or above our desired target.
         */
        if (currentSpeedSquared >= maxSpeedPerTickSquared) {
            geometryLimitedMinecarts.remove(minecart.getUniqueId());
            return;
        }

        double currentSpeed = Math.sqrt(currentSpeedSquared);

        double newSpeed = Math.min(
                maxSpeedPerTick,
                currentSpeed + accelerationPerTick
        );

        if (newSpeed <= currentSpeed) {
            return;
        }

        scaleVelocity(minecart, velocity, newSpeed / currentSpeed);

        if (newSpeed >= maxSpeedPerTick) {
            geometryLimitedMinecarts.remove(minecart.getUniqueId());
        }
    }

    @EventHandler
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (event.getVehicle() instanceof Minecart minecart) {
            geometryLimitedMinecarts.remove(minecart.getUniqueId());
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (isRail(event.getBlock())) {
            invalidateGeometryCacheNear(event.getBlock());
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (isRail(event.getBlock())) {
            invalidateGeometryCacheNear(event.getBlock());
        }
    }

    @EventHandler
    public void onBlockPhysics(BlockPhysicsEvent event) {
        if (isRail(event.getBlock())) {
            invalidateGeometryCacheNear(event.getBlock());
        }
    }

    private boolean isBoostLocation(Location location) {
        Block rail = findAssociatedRail(location);
        return rail != null && isBoostRail(rail);
    }

    private boolean isBoostRail(Block block) {
        Material material = block.getType();

        /*
         * Powered rails only boost when actually powered.
         *
         * This deliberately preserves the vanilla behavior where an
         * unpowered powered rail can act as a brake.
         */
        if (material == Material.POWERED_RAIL) {
            if (!(block.getBlockData() instanceof Powerable powerable)) {
                return false;
            }

            return powerable.isPowered();
        }

        /*
         * Optional mode for servers that want ordinary rails to maintain and
         * increase high-speed travel without requiring continuous powered rail.
         */
        return boostOnAllRails && Tag.RAILS.isTagged(material);
    }

    private double findCurveSpeedLimit(
            VehicleMoveEvent event,
            BlockFace travelFace,
            double currentSpeedSquared
    ) {
        Block toRail = findAssociatedRail(event.getTo());

        if (isCurvedRail(toRail)) {
            return curveSpeedPerTick;
        }

        Block fromRail = findAssociatedRail(event.getFrom());

        if (isCurvedRail(fromRail)) {
            return curveSpeedPerTick;
        }

        Block startRail = toRail != null ? toRail : fromRail;

        if (startRail == null) {
            return -1.0;
        }

        int lookaheadBlocks = calculateLookaheadBlocks(currentSpeedSquared);
        return findCachedGeometrySpeedLimit(
                startRail,
                travelFace,
                lookaheadBlocks,
                GeometryKind.CURVE
        );
    }

    private double findInclineSpeedLimitAhead(
            VehicleMoveEvent event,
            BlockFace travelFace,
            double currentSpeedSquared
    ) {
        Block toRail = findAssociatedRail(event.getTo());
        Block fromRail = findAssociatedRail(event.getFrom());

        Block startRail = toRail;

        if (startRail == null) {
            startRail = fromRail;
        }

        if (startRail == null) {
            return -1.0;
        }

        int lookaheadBlocks = calculateLookaheadBlocks(currentSpeedSquared);
        return findCachedGeometrySpeedLimit(
                startRail,
                travelFace,
                lookaheadBlocks,
                GeometryKind.INCLINE
        );
    }

    private double findVanillaRailSpeedLimit(
            VehicleMoveEvent event,
            BlockFace travelFace,
            double currentSpeedSquared
    ) {
        Block toRail = findAssociatedRail(event.getTo());

        if (isVanillaOnlyRail(toRail)) {
            return VANILLA_MAX_SPEED_PER_TICK;
        }

        Block fromRail = findAssociatedRail(event.getFrom());

        if (isVanillaOnlyRail(fromRail)) {
            return VANILLA_MAX_SPEED_PER_TICK;
        }

        Block startRail = toRail != null ? toRail : fromRail;

        if (startRail == null) {
            return -1.0;
        }

        int lookaheadBlocks = calculateLookaheadBlocks(currentSpeedSquared);

        return findCachedGeometrySpeedLimit(
                startRail,
                travelFace,
                lookaheadBlocks,
                GeometryKind.VANILLA_RAIL
        );
    }

    private boolean hasStraightRailAhead(
            VehicleMoveEvent event,
            Vector velocity,
            int requiredBlocks
    ) {
        BlockFace travelFace = getPrimaryTravelFace(velocity);

        if (travelFace == null) {
            return false;
        }

        Block startRail = findAssociatedRail(event.getTo());

        if (startRail == null) {
            startRail = findAssociatedRail(event.getFrom());
        }

        if (startRail == null || !isStraightRailForTravel(startRail, travelFace)) {
            return false;
        }

        return hasStraightRailAhead(startRail, travelFace, requiredBlocks);
    }

    private int calculateLookaheadBlocks(double currentSpeedSquared) {
        return Math.min(
                MAX_CURVE_LOOKAHEAD_BLOCKS,
                (int) Math.ceil(Math.sqrt(currentSpeedSquared) * CURVE_LOOKAHEAD_TICKS)
                        + CURVE_LOOKAHEAD_EXTRA_BLOCKS
        );
    }

    private double findCachedGeometrySpeedLimit(
            Block startRail,
            BlockFace travelFace,
            int lookaheadBlocks,
            GeometryKind kind
    ) {
        GeometryCacheKey cacheKey = GeometryCacheKey.from(
                startRail,
                travelFace,
                kind
        );

        long now = System.currentTimeMillis();
        GeometryCacheEntry cachedEntry = geometryCache.get(cacheKey);

        if (cachedEntry != null
                && cachedEntry.expiresAtMillis() >= now
                && cachedEntry.scannedBlocks() >= lookaheadBlocks) {
            return cachedEntry.speedLimit();
        }

        double speedLimit = scanGeometrySpeedLimit(
                startRail,
                travelFace,
                lookaheadBlocks,
                kind
        );

        geometryCache.put(
                cacheKey,
                new GeometryCacheEntry(
                        speedLimit,
                        lookaheadBlocks,
                        now + GEOMETRY_CACHE_TTL_MILLIS
                )
        );

        return speedLimit;
    }

    private double scanGeometrySpeedLimit(
            Block startRail,
            BlockFace travelFace,
            int lookaheadBlocks,
            GeometryKind kind
    ) {
        Block scanRail = startRail;

        for (int distance = 0; distance < lookaheadBlocks; distance++) {
            Block nextBlock = scanRail.getRelative(travelFace);
            Block nextRail = findRailNearTrajectory(nextBlock);

            if (nextRail == null) {
                scanRail = nextBlock;
                continue;
            }

            if (matchesGeometryKind(nextRail, kind)) {
                return geometrySpeedLimit(kind);
            }

            scanRail = nextRail;
        }

        return -1.0;
    }

    private boolean hasStraightRailAhead(
            Block startRail,
            BlockFace travelFace,
            int requiredBlocks
    ) {
        GeometryCacheKey cacheKey = GeometryCacheKey.from(
                startRail,
                travelFace,
                GeometryKind.STRAIGHT
        );

        long now = System.currentTimeMillis();
        GeometryCacheEntry cachedEntry = geometryCache.get(cacheKey);

        if (cachedEntry != null
                && cachedEntry.expiresAtMillis() >= now
                && cachedEntry.scannedBlocks() >= requiredBlocks) {
            return cachedEntry.speedLimit() > 0.0;
        }

        boolean hasStraightRail = scanStraightRailAhead(
                startRail,
                travelFace,
                requiredBlocks
        );

        geometryCache.put(
                cacheKey,
                new GeometryCacheEntry(
                        hasStraightRail ? 1.0 : -1.0,
                        requiredBlocks,
                        now + GEOMETRY_CACHE_TTL_MILLIS
                )
        );

        return hasStraightRail;
    }

    private boolean scanStraightRailAhead(
            Block startRail,
            BlockFace travelFace,
            int requiredBlocks
    ) {
        Block scanRail = startRail;

        for (int distance = 0; distance < requiredBlocks; distance++) {
            Block nextBlock = scanRail.getRelative(travelFace);
            Block nextRail = findRailNearTrajectory(nextBlock);

            if (nextRail == null || !isStraightRailForTravel(nextRail, travelFace)) {
                return false;
            }

            scanRail = nextRail;
        }

        return true;
    }

    private boolean matchesGeometryKind(Block block, GeometryKind kind) {
        return switch (kind) {
            case CURVE -> isCurvedRail(block);
            case INCLINE -> isInclineRail(block);
            case VANILLA_RAIL -> isVanillaOnlyRail(block);
            case STRAIGHT -> false;
        };
    }

    private double geometrySpeedLimit(GeometryKind kind) {
        return switch (kind) {
            case CURVE -> curveSpeedPerTick;
            case INCLINE -> VANILLA_MAX_SPEED_PER_TICK;
            case VANILLA_RAIL -> VANILLA_MAX_SPEED_PER_TICK;
            case STRAIGHT -> -1.0;
        };
    }

    private void clearGeometryLimitedStateIfOffRail(
            Minecart minecart,
            VehicleMoveEvent event
    ) {
        if (findAssociatedRail(event.getTo()) == null
                && findAssociatedRail(event.getFrom()) == null) {
            geometryLimitedMinecarts.remove(minecart.getUniqueId());
        }
    }

    private void invalidateGeometryCacheNear(Block changedBlock) {
        if (geometryCache.isEmpty()) {
            return;
        }

        UUID worldId = changedBlock.getWorld().getUID();
        int x = changedBlock.getX();
        int y = changedBlock.getY();
        int z = changedBlock.getZ();

        Iterator<GeometryCacheKey> iterator = geometryCache.keySet().iterator();

        while (iterator.hasNext()) {
            GeometryCacheKey key = iterator.next();

            if (!key.worldId().equals(worldId)) {
                continue;
            }

            if (Math.abs(key.x() - x) <= MAX_CURVE_LOOKAHEAD_BLOCKS
                    && Math.abs(key.y() - y) <= 2
                    && Math.abs(key.z() - z) <= MAX_CURVE_LOOKAHEAD_BLOCKS) {
                iterator.remove();
            }
        }
    }

    private Block findAssociatedRail(Location location) {
        Block block = location.getBlock();

        if (isRail(block)) {
            return block;
        }

        Block below = block.getRelative(BlockFace.DOWN);

        if (isRail(below)) {
            return below;
        }

        return null;
    }

    private Block findRailNearTrajectory(Block block) {
        if (isRail(block)) {
            return block;
        }

        Block below = block.getRelative(BlockFace.DOWN);

        if (isRail(below)) {
            return below;
        }

        Block above = block.getRelative(BlockFace.UP);

        if (isRail(above)) {
            return above;
        }

        return null;
    }

    private boolean isRail(Block block) {
        return Tag.RAILS.isTagged(block.getType());
    }

    private boolean isCurvedRail(Block block) {
        if (block == null || !(block.getBlockData() instanceof Rail rail)) {
            return false;
        }

        return isCurvedShape(rail.getShape());
    }

    private boolean isInclineRail(Block block) {
        if (block == null || !(block.getBlockData() instanceof Rail rail)) {
            return false;
        }

        return isInclineShape(rail.getShape());
    }

    private boolean isVanillaOnlyRail(Block block) {
        if (block == null) {
            return false;
        }

        Material material = block.getType();

        return material == Material.DETECTOR_RAIL
                || material == Material.ACTIVATOR_RAIL;
    }

    private boolean isOnIncline(VehicleMoveEvent event) {
        return isInclineRail(findAssociatedRail(event.getTo()))
                || isInclineRail(findAssociatedRail(event.getFrom()));
    }

    private boolean isCurvedShape(Rail.Shape shape) {
        return shape == Rail.Shape.NORTH_EAST
                || shape == Rail.Shape.NORTH_WEST
                || shape == Rail.Shape.SOUTH_EAST
                || shape == Rail.Shape.SOUTH_WEST;
    }

    private boolean isInclineShape(Rail.Shape shape) {
        return shape == Rail.Shape.ASCENDING_EAST
                || shape == Rail.Shape.ASCENDING_WEST
                || shape == Rail.Shape.ASCENDING_NORTH
                || shape == Rail.Shape.ASCENDING_SOUTH;
    }

    private boolean isStraightRailForTravel(Block block, BlockFace travelFace) {
        if (!(block.getBlockData() instanceof Rail rail)) {
            return false;
        }

        Rail.Shape shape = rail.getShape();

        return switch (travelFace) {
            case NORTH, SOUTH -> shape == Rail.Shape.NORTH_SOUTH
                    || shape == Rail.Shape.ASCENDING_NORTH
                    || shape == Rail.Shape.ASCENDING_SOUTH;
            case EAST, WEST -> shape == Rail.Shape.EAST_WEST
                    || shape == Rail.Shape.ASCENDING_EAST
                    || shape == Rail.Shape.ASCENDING_WEST;
            default -> false;
        };
    }

    private BlockFace getPrimaryTravelFace(Vector velocity) {
        double x = velocity.getX();
        double z = velocity.getZ();

        if ((x * x) + (z * z) < MIN_VELOCITY_SQUARED) {
            return null;
        }

        if (Math.abs(x) > Math.abs(z)) {
            return x > 0.0 ? BlockFace.EAST : BlockFace.WEST;
        }

        return z > 0.0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    private void limitVelocity(
            Minecart minecart,
            Vector velocity,
            double currentSpeedSquared,
            double targetSpeed
    ) {
        if (targetSpeed <= 0.0) {
            minecart.setVelocity(new Vector(0.0, 0.0, 0.0));
            return;
        }

        double currentSpeed = Math.sqrt(currentSpeedSquared);

        if (currentSpeed <= targetSpeed) {
            return;
        }

        scaleVelocity(minecart, velocity, targetSpeed / currentSpeed);
    }

    private void scaleVelocity(
            Minecart minecart,
            Vector velocity,
            double multiplier
    ) {
        /*
         * Preserve Minecraft's calculated direction.
         *
         * This is important for:
         * - corners
         * - slopes
         * - direction changes
         *
         * We alter only the magnitude of the velocity vector instead of
         * deciding which direction the minecart should travel ourselves.
         */
        velocity.multiply(multiplier);
        minecart.setVelocity(velocity);
    }

    /*
     * -------------------------------------------------------------------------
     * Command
     * -------------------------------------------------------------------------
     */

    private void handleCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendStatus(sender);
            return;
        }

        /*
         * /minecartspeed reload
         */
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            installConfigDefaults();
            loadSettings();

            int changed = applyToAllLoadedMinecarts();

            sender.sendMessage(String.format(
                    Locale.ROOT,
                    "FastMinecarts reloaded. Speed: %.2f blocks/sec, acceleration: %.2f blocks/sec^2, curve speed: %.2f blocks/sec. Updated %d loaded minecart(s).",
                    speedBlocksPerSecond,
                    accelerationBlocksPerSecondSquared,
                    curveSpeedBlocksPerSecond,
                    changed
            ));

            return;
        }

        /*
         * /minecartspeed acceleration <number>
         * /minecartspeed accel <number>
         */
        if (args[0].equalsIgnoreCase("acceleration")
                || args[0].equalsIgnoreCase("accel")) {

            if (args.length != 2) {
                sender.sendMessage(
                        "Usage: /minecartspeed acceleration <blocks/sec^2>"
                );
                return;
            }

            Double requestedAcceleration = parseNumber(
                    sender,
                    args[1],
                    "Acceleration"
            );

            if (requestedAcceleration == null) {
                return;
            }

            if (requestedAcceleration < 0.0
                    || requestedAcceleration > MAX_ALLOWED_ACCELERATION_BPS2) {

                sender.sendMessage(String.format(
                        Locale.ROOT,
                        "Acceleration must be between 0 and %.0f blocks/sec^2.",
                        MAX_ALLOWED_ACCELERATION_BPS2
                ));

                return;
            }

            accelerationBlocksPerSecondSquared = requestedAcceleration;

            recalculateCachedSettings();

            getConfig().set(
                    "acceleration-blocks-per-second-squared",
                    accelerationBlocksPerSecondSquared
            );

            saveConfig();

            sender.sendMessage(String.format(
                    Locale.ROOT,
                    "Minecart acceleration set to %.2f blocks/sec^2.",
                    accelerationBlocksPerSecondSquared
            ));

            return;
        }

        /*
         * /minecartspeed curvespeed <number>
         * /minecartspeed curve <number>
         */
        if (args[0].equalsIgnoreCase("curvespeed")
                || args[0].equalsIgnoreCase("curve")) {

            if (args.length != 2) {
                sender.sendMessage(
                        "Usage: /minecartspeed curvespeed <blocks/sec>"
                );
                return;
            }

            Double requestedCurveSpeed = parseNumber(
                    sender,
                    args[1],
                    "Curve speed"
            );

            if (requestedCurveSpeed == null) {
                return;
            }

            if (requestedCurveSpeed < 0.0
                    || requestedCurveSpeed > MAX_ALLOWED_SPEED_BPS) {

                sender.sendMessage(String.format(
                        Locale.ROOT,
                        "Curve speed must be between 0 and %.0f blocks/sec.",
                        MAX_ALLOWED_SPEED_BPS
                ));

                return;
            }

            curveSpeedBlocksPerSecond = requestedCurveSpeed;
            recalculateCachedSettings();

            getConfig().set(
                    "curve-speed-blocks-per-second",
                    curveSpeedBlocksPerSecond
            );

            saveConfig();

            sender.sendMessage(String.format(
                    Locale.ROOT,
                    "Minecart curve speed set to %.2f blocks/sec.",
                    curveSpeedBlocksPerSecond
            ));

            return;
        }

        /*
         * Normal syntax remains backwards-compatible:
         *
         * /minecartspeed 32
         */
        if (args.length != 1) {
            sendUsage(sender);
            return;
        }

        Double requestedSpeed = parseNumber(
                sender,
                args[0],
                "Speed"
        );

        if (requestedSpeed == null) {
            return;
        }

        if (requestedSpeed < 0.0
                || requestedSpeed > MAX_ALLOWED_SPEED_BPS) {

            sender.sendMessage(String.format(
                    Locale.ROOT,
                    "Speed must be between 0 and %.0f blocks/sec.",
                    MAX_ALLOWED_SPEED_BPS
            ));

            return;
        }

        speedBlocksPerSecond = requestedSpeed;

        recalculateCachedSettings();

        getConfig().set(
                "speed-blocks-per-second",
                speedBlocksPerSecond
        );

        saveConfig();

        int changed = applyToAllLoadedMinecarts();

        sender.sendMessage(String.format(
                Locale.ROOT,
                "Minecart target speed set to %.2f blocks/sec. Updated %d loaded minecart(s).",
                speedBlocksPerSecond,
                changed
        ));

        if (speedBlocksPerSecond > 100.0) {
            sender.sendMessage(
                    "Warning: speeds above 100 blocks/sec may cause chunk-loading, collision, or visual problems."
            );
        }
    }

    private Double parseNumber(
            CommandSender sender,
            String input,
            String name
    ) {
        final double value;

        try {
            value = Double.parseDouble(input);
        } catch (NumberFormatException exception) {
            sender.sendMessage(
                    name + " must be a number."
            );

            return null;
        }

        if (!Double.isFinite(value)) {
            sender.sendMessage(
                    name + " must be a finite number."
            );

            return null;
        }

        return value;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(String.format(
                Locale.ROOT,
                "Minecart speed: %.2f blocks/sec | Acceleration: %.2f blocks/sec^2 | Curve speed: %.2f blocks/sec | Slopes: vanilla | Boost all rails: %s | Slow when empty: %s",
                speedBlocksPerSecond,
                accelerationBlocksPerSecondSquared,
                curveSpeedBlocksPerSecond,
                boostOnAllRails,
                slowWhenEmpty
        ));

        sendUsage(sender);
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(
                "Usage: /minecartspeed <speed|reload|acceleration <value>|curvespeed <value>>"
        );
    }

    private Collection<String> handleSuggestions(String[] args) {
        if (args.length == 0) {
            return List.of(
                    "reload",
                    "acceleration",
                    "curvespeed",
                    "8",
                    "16",
                    "24",
                    "32",
                    "40",
                    "64",
                    "100"
            );
        }

        if (args.length == 1) {
            return matchingSuggestions(
                    args[0],
                    List.of(
                            "reload",
                            "acceleration",
                            "curvespeed",
                            "8",
                            "16",
                            "24",
                            "32",
                            "40",
                            "64",
                            "100"
                    )
            );
        }

        if (args.length == 2
                && (args[0].equalsIgnoreCase("acceleration")
                || args[0].equalsIgnoreCase("accel"))) {

            return matchingSuggestions(
                    args[1],
                    List.of(
                            "20",
                            "40",
                            "80",
                            "160",
                            "320"
                    )
            );
        }

        if (args.length == 2
                && (args[0].equalsIgnoreCase("curvespeed")
                || args[0].equalsIgnoreCase("curve"))) {

            return matchingSuggestions(
                    args[1],
                    List.of(
                            "8",
                            "10",
                            "12",
                            "14",
                            "16"
                    )
            );
        }

        return List.of();
    }

    private Collection<String> matchingSuggestions(
            String input,
            List<String> options
    ) {
        String lowerInput = input.toLowerCase(Locale.ROOT);

        List<String> matches = new ArrayList<>();

        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lowerInput)) {
                matches.add(option);
            }
        }

        return matches;
    }
}
