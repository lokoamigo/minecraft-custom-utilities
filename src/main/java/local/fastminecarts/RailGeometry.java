package local.fastminecarts;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Rail;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.util.Vector;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class RailGeometry {
    private static final double MIN_VELOCITY_SQUARED = 1.0E-8;
    private static final int LOOKAHEAD_TICKS = 3;
    private static final int LOOKAHEAD_EXTRA_BLOCKS = 2;
    private static final int MAX_LOOKAHEAD_BLOCKS = 64;
    private static final int CACHE_MAX_ENTRIES = 4096;
    private static final long CACHE_TTL_MILLIS = 5000L;

    private final MinecartSettings settings;
    private final Map<CacheKey, CacheEntry> cache = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, CacheEntry> eldest) {
            return size() > CACHE_MAX_ENTRIES;
        }
    };

    RailGeometry(MinecartSettings settings) {
        this.settings = settings;
    }

    double findCurveSpeedLimit(VehicleMoveEvent event, BlockFace direction,
                               double currentSpeedSquared) {
        Block toRail = findAssociatedRail(event.getTo());
        if (isCurvedRail(toRail)) return settings.curveSpeedPerTick();
        Block fromRail = findAssociatedRail(event.getFrom());
        if (isCurvedRail(fromRail)) return settings.curveSpeedPerTick();
        Block start = toRail != null ? toRail : fromRail;
        return start == null ? -1.0 : findCachedLimit(start, direction,
                calculateLookahead(currentSpeedSquared), Kind.CURVE);
    }

    double findInclineSpeedLimit(VehicleMoveEvent event, BlockFace direction,
                                 double currentSpeedSquared) {
        Block toRail = findAssociatedRail(event.getTo());
        Block start = toRail != null ? toRail : findAssociatedRail(event.getFrom());
        return start == null ? -1.0 : findCachedLimit(start, direction,
                calculateLookahead(currentSpeedSquared), Kind.INCLINE);
    }

    double findVanillaRailSpeedLimit(VehicleMoveEvent event, BlockFace direction,
                                     double currentSpeedSquared) {
        Block toRail = findAssociatedRail(event.getTo());
        if (isVanillaOnlyRail(toRail)) return MinecartSettings.VANILLA_MAX_SPEED_PER_TICK;
        Block fromRail = findAssociatedRail(event.getFrom());
        if (isVanillaOnlyRail(fromRail)) return MinecartSettings.VANILLA_MAX_SPEED_PER_TICK;
        Block start = toRail != null ? toRail : fromRail;
        return start == null ? -1.0 : findCachedLimit(start, direction,
                calculateLookahead(currentSpeedSquared), Kind.VANILLA_RAIL);
    }

    boolean hasStraightRailAhead(VehicleMoveEvent event, Vector velocity, int requiredBlocks) {
        BlockFace direction = primaryTravelFace(velocity);
        if (direction == null) return false;
        Block start = findAssociatedRail(event.getTo());
        if (start == null) start = findAssociatedRail(event.getFrom());
        if (start == null || !isStraightRailForTravel(start, direction)) return false;
        return hasStraightRailAhead(start, direction, requiredBlocks);
    }

    boolean isOnIncline(VehicleMoveEvent event) {
        return isInclineRail(findAssociatedRail(event.getTo()))
                || isInclineRail(findAssociatedRail(event.getFrom()));
    }

    boolean isRail(Block block) {
        return block != null && Tag.RAILS.isTagged(block.getType());
    }

    boolean isOffRail(VehicleMoveEvent event) {
        return findAssociatedRail(event.getTo()) == null
                && findAssociatedRail(event.getFrom()) == null;
    }

    Block findAssociatedRail(Location location) {
        Block block = location.getBlock();
        if (isRail(block)) return block;
        Block below = block.getRelative(BlockFace.DOWN);
        return isRail(below) ? below : null;
    }

    BlockFace primaryTravelFace(Vector velocity) {
        double x = velocity.getX();
        double z = velocity.getZ();
        if ((x * x) + (z * z) < MIN_VELOCITY_SQUARED) return null;
        if (Math.abs(x) > Math.abs(z)) return x > 0.0 ? BlockFace.EAST : BlockFace.WEST;
        return z > 0.0 ? BlockFace.SOUTH : BlockFace.NORTH;
    }

    void clearCache() {
        cache.clear();
    }

    void invalidateNear(Block changedBlock) {
        if (cache.isEmpty()) return;
        UUID worldId = changedBlock.getWorld().getUID();
        Iterator<CacheKey> iterator = cache.keySet().iterator();
        while (iterator.hasNext()) {
            CacheKey key = iterator.next();
            if (key.worldId().equals(worldId)
                    && Math.abs(key.x() - changedBlock.getX()) <= MAX_LOOKAHEAD_BLOCKS
                    && Math.abs(key.y() - changedBlock.getY()) <= 2
                    && Math.abs(key.z() - changedBlock.getZ()) <= MAX_LOOKAHEAD_BLOCKS) {
                iterator.remove();
            }
        }
    }

    private int calculateLookahead(double speedSquared) {
        return Math.min(MAX_LOOKAHEAD_BLOCKS,
                (int) Math.ceil(Math.sqrt(speedSquared) * LOOKAHEAD_TICKS)
                        + LOOKAHEAD_EXTRA_BLOCKS);
    }

    private double findCachedLimit(Block start, BlockFace direction, int blocks, Kind kind) {
        CacheKey key = CacheKey.from(start, direction, kind);
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.expiresAtMillis() >= now && entry.scannedBlocks() >= blocks) {
            return entry.speedLimit();
        }
        double limit = scanLimit(start, direction, blocks, kind);
        cache.put(key, new CacheEntry(limit, blocks, now + CACHE_TTL_MILLIS));
        return limit;
    }

    private double scanLimit(Block start, BlockFace direction, int blocks, Kind kind) {
        Block scanRail = start;
        for (int distance = 0; distance < blocks; distance++) {
            Block nextBlock = scanRail.getRelative(direction);
            Block nextRail = findRailNearTrajectory(nextBlock);
            if (nextRail == null) {
                scanRail = nextBlock;
                continue;
            }
            if (matches(nextRail, kind)) return speedLimit(kind);
            scanRail = nextRail;
        }
        return -1.0;
    }

    private boolean hasStraightRailAhead(Block start, BlockFace direction, int blocks) {
        CacheKey key = CacheKey.from(start, direction, Kind.STRAIGHT);
        long now = System.currentTimeMillis();
        CacheEntry entry = cache.get(key);
        if (entry != null && entry.expiresAtMillis() >= now && entry.scannedBlocks() >= blocks) {
            return entry.speedLimit() > 0.0;
        }
        boolean result = scanStraight(start, direction, blocks);
        cache.put(key, new CacheEntry(result ? 1.0 : -1.0, blocks, now + CACHE_TTL_MILLIS));
        return result;
    }

    private boolean scanStraight(Block start, BlockFace direction, int blocks) {
        Block scanRail = start;
        for (int distance = 0; distance < blocks; distance++) {
            Block nextRail = findRailNearTrajectory(scanRail.getRelative(direction));
            if (nextRail == null || !isStraightRailForTravel(nextRail, direction)) return false;
            scanRail = nextRail;
        }
        return true;
    }

    private Block findRailNearTrajectory(Block block) {
        if (isRail(block)) return block;
        Block below = block.getRelative(BlockFace.DOWN);
        if (isRail(below)) return below;
        Block above = block.getRelative(BlockFace.UP);
        return isRail(above) ? above : null;
    }

    private boolean matches(Block block, Kind kind) {
        return switch (kind) {
            case CURVE -> isCurvedRail(block);
            case INCLINE -> isInclineRail(block);
            case VANILLA_RAIL -> isVanillaOnlyRail(block);
            case STRAIGHT -> false;
        };
    }

    private double speedLimit(Kind kind) {
        return kind == Kind.CURVE ? settings.curveSpeedPerTick()
                : kind == Kind.STRAIGHT ? -1.0 : MinecartSettings.VANILLA_MAX_SPEED_PER_TICK;
    }

    private boolean isCurvedRail(Block block) {
        return block != null && block.getBlockData() instanceof Rail rail
                && switch (rail.getShape()) {
                    case NORTH_EAST, NORTH_WEST, SOUTH_EAST, SOUTH_WEST -> true;
                    default -> false;
                };
    }

    private boolean isInclineRail(Block block) {
        return block != null && block.getBlockData() instanceof Rail rail
                && switch (rail.getShape()) {
                    case ASCENDING_EAST, ASCENDING_WEST, ASCENDING_NORTH, ASCENDING_SOUTH -> true;
                    default -> false;
                };
    }

    private boolean isVanillaOnlyRail(Block block) {
        if (block == null) return false;
        Material material = block.getType();
        return material == Material.DETECTOR_RAIL || material == Material.ACTIVATOR_RAIL;
    }

    private boolean isStraightRailForTravel(Block block, BlockFace direction) {
        if (!(block.getBlockData() instanceof Rail rail)) return false;
        return switch (direction) {
            case NORTH, SOUTH -> rail.getShape() == Rail.Shape.NORTH_SOUTH
                    || rail.getShape() == Rail.Shape.ASCENDING_NORTH
                    || rail.getShape() == Rail.Shape.ASCENDING_SOUTH;
            case EAST, WEST -> rail.getShape() == Rail.Shape.EAST_WEST
                    || rail.getShape() == Rail.Shape.ASCENDING_EAST
                    || rail.getShape() == Rail.Shape.ASCENDING_WEST;
            default -> false;
        };
    }

    private enum Kind { CURVE, INCLINE, VANILLA_RAIL, STRAIGHT }
    private record CacheEntry(double speedLimit, int scannedBlocks, long expiresAtMillis) {}
    private record CacheKey(UUID worldId, int x, int y, int z, BlockFace direction, Kind kind) {
        static CacheKey from(Block block, BlockFace direction, Kind kind) {
            return new CacheKey(block.getWorld().getUID(), block.getX(), block.getY(),
                    block.getZ(), direction, kind);
        }
    }
}
