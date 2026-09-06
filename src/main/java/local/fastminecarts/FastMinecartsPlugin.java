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
import org.bukkit.entity.Minecart;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class FastMinecartsPlugin extends JavaPlugin implements Listener {

    private static final double TICKS_PER_SECOND = 20.0;

    private static final double DEFAULT_SPEED_BPS = 8.0;
    private static final double DEFAULT_ACCELERATION_BPS2 = 0.0;
    private static final double DEFAULT_CURVE_SPEED_BPS = 8.0;

    private static final double MAX_ALLOWED_SPEED_BPS = 1000.0;
    private static final double MAX_ALLOWED_ACCELERATION_BPS2 = 10000.0;

    private static final int CURVE_LOOKAHEAD_TICKS = 3;
    private static final int CURVE_LOOKAHEAD_EXTRA_BLOCKS = 2;
    private static final int MAX_CURVE_LOOKAHEAD_BLOCKS = 64;

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
    private double curveSpeedPerTickSquared;

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

        curveSpeedPerTickSquared =
                curveSpeedPerTick * curveSpeedPerTick;
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
            applyMinecartSettings(minecart);
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

    private int applyToAllLoadedMinecarts() {
        int changed = 0;

        for (World world : Bukkit.getWorlds()) {
            /*
             * More efficient than world.getEntities() followed by checking
             * every entity on the server with instanceof.
             */
            for (Minecart minecart : world.getEntitiesByClass(Minecart.class)) {
                if (applyMinecartSettings(minecart)) {
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

        if (shouldLimitForCurve(event, velocity, currentSpeedSquared)) {
            limitVelocity(minecart, velocity, currentSpeedSquared, curveSpeedPerTick);
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
        if (!isBoostLocation(event.getTo())
                && !isBoostLocation(event.getFrom())) {
            return;
        }

        /*
         * Already at or above our desired target.
         */
        if (currentSpeedSquared >= maxSpeedPerTickSquared) {
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

    private boolean shouldLimitForCurve(
            VehicleMoveEvent event,
            Vector velocity,
            double currentSpeedSquared
    ) {
        if (curveSpeedPerTick < 0.0
                || currentSpeedSquared <= curveSpeedPerTickSquared) {
            return false;
        }

        Block toRail = findAssociatedRail(event.getTo());

        if (isCurvedRail(toRail)) {
            return true;
        }

        Block fromRail = findAssociatedRail(event.getFrom());

        if (isCurvedRail(fromRail)) {
            return true;
        }

        BlockFace travelFace = getPrimaryTravelFace(velocity);

        if (travelFace == null) {
            return false;
        }

        Block startRail = toRail != null ? toRail : fromRail;

        if (startRail == null) {
            return false;
        }

        int lookaheadBlocks = Math.min(
                MAX_CURVE_LOOKAHEAD_BLOCKS,
                (int) Math.ceil(Math.sqrt(currentSpeedSquared) * CURVE_LOOKAHEAD_TICKS)
                        + CURVE_LOOKAHEAD_EXTRA_BLOCKS
        );

        Block scanRail = startRail;

        for (int distance = 0; distance < lookaheadBlocks; distance++) {
            Block nextBlock = scanRail.getRelative(travelFace);
            Block nextRail = findRailNearTrajectory(nextBlock);

            if (nextRail == null) {
                scanRail = nextBlock;
                continue;
            }

            if (isCurvedRail(nextRail)) {
                return true;
            }

            scanRail = nextRail;
        }

        return false;
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

        Rail.Shape shape = rail.getShape();

        return shape == Rail.Shape.NORTH_EAST
                || shape == Rail.Shape.NORTH_WEST
                || shape == Rail.Shape.SOUTH_EAST
                || shape == Rail.Shape.SOUTH_WEST;
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
                "Minecart speed: %.2f blocks/sec | Acceleration: %.2f blocks/sec^2 | Curve speed: %.2f blocks/sec | Boost all rails: %s | Slow when empty: %s",
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
