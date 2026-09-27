package local.fastminecarts;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Lectern;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.LecternInventory;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Opens the vanilla book editor for the writable book stored in a lectern.
 *
 * <p>Vanilla only accepts a book edit submission for the player's held book. During an edit
 * session the server therefore temporarily puts a copy of the lectern's book in the selected
 * hotbar slot. The original item stays on the lectern and is replaced only after the player
 * submits the editor.</p>
 */
final class LecternBookEditor implements Listener {
    private static final String PERMISSION = "minecraftcustomutilities.lecternedit";
    private static final long SESSION_TIMEOUT_TICKS = 20L * 60L * 5L;

    private final Plugin plugin;
    private final Map<UUID, EditSession> sessions = new HashMap<>();
    private final Map<UUID, EditSession> pendingRestores = new HashMap<>();
    private final Map<LecternLocation, UUID> locks = new HashMap<>();

    LecternBookEditor(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLecternInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !event.getAction().isRightClick()
                || event.getClickedBlock() == null || event.getClickedBlock().getType() != Material.LECTERN) {
            return;
        }

        Player player = event.getPlayer();
        finishIfEditing(player);
        restorePending(player);
        if (!player.isSneaking() || !player.hasPermission(PERMISSION)) {
            return;
        }

        Block block = event.getClickedBlock();
        Lectern lectern = (Lectern) block.getState();
        ItemStack book = lecternInventory(lectern).getBook();
        if (book == null || book.getType() != Material.WRITABLE_BOOK) {
            player.sendMessage("This lectern needs a writable book to edit it.");
            event.setCancelled(true);
            return;
        }

        LecternLocation location = LecternLocation.of(block);
        UUID editor = locks.get(location);
        if (editor != null) {
            player.sendMessage("Someone is already editing this lectern book.");
            event.setCancelled(true);
            return;
        }

        PlayerInventory inventory = player.getInventory();
        int heldSlot = inventory.getHeldItemSlot();
        ItemStack heldItem = inventory.getItem(heldSlot);
        EditSession session = new EditSession(location, book.clone(), copy(heldItem), heldSlot);
        sessions.put(player.getUniqueId(), session);
        locks.put(location, player.getUniqueId());
        inventory.setItem(heldSlot, book.clone());

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> openEditor(player, session));
        Bukkit.getScheduler().runTaskLater(plugin, () -> finishIfCurrent(player, session), SESSION_TIMEOUT_TICKS);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBookEdit(PlayerEditBookEvent event) {
        Player player = event.getPlayer();
        EditSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }

        // Creative inventory packets do not consistently report hotbar slots. The previous
        // metadata is the original temporary book and stays stable regardless of whether Paper
        // has already applied the submitted pages to the live inventory item.
        if (!event.getPreviousBookMeta().equals(session.originalBook.getItemMeta())) {
            return;
        }

        event.setCancelled(true);
        try {
            Block block = session.location.block();
            if (block == null || block.getType() != Material.LECTERN) {
                player.sendMessage("The lectern was removed; your changes were not saved.");
                return;
            }

            Lectern lectern = (Lectern) block.getState();
            ItemStack current = lecternInventory(lectern).getBook();
            if (!sameBook(current, session.originalBook)) {
                player.sendMessage("The lectern book changed; your changes were not saved.");
                return;
            }

            ItemStack updated = session.originalBook.clone();
            if (event.isSigning()) {
                updated.setType(Material.WRITTEN_BOOK);
            }
            BookMeta meta = event.getNewBookMeta();
            updated.setItemMeta(meta);
            // TileStateInventoryHolder#getInventory is the live inventory. Calling update() on
            // its BlockState afterwards would write the stale snapshot back over this book.
            lecternInventory(lectern).setBook(updated);
            player.sendMessage("Lectern book updated.");
        } finally {
            finishAfterBookEdit(player, session);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTakeLecternBook(PlayerTakeLecternBookEvent event) {
        if (locks.containsKey(LecternLocation.of(event.getLectern().getBlock()))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("This lectern book is being edited.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLecternBreak(BlockBreakEvent event) {
        if (locks.containsKey(LecternLocation.of(event.getBlock()))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("This lectern book is being edited.");
        }
    }

    @EventHandler
    public void onHeldItemChange(PlayerItemHeldEvent event) {
        EditSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            finish(event.getPlayer(), session);
        }
        restorePending(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && finishIfEditing(player)) {
            // The click was calculated against the temporary item, so require one more click
            // after restoring the player's real selected stack.
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && finishIfEditing(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (finishIfEditing(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        EditSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            finish(event.getPlayer(), session);
        }
        restorePending(event.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        UUID playerId = event.getEntity().getUniqueId();
        EditSession session = sessions.get(playerId);
        boolean wasPendingRestore = false;
        if (session == null) {
            session = pendingRestores.remove(playerId);
            wasPendingRestore = session != null;
        }
        if (session != null) {
            if (event.getKeepInventory()) {
                if (wasPendingRestore) {
                    restoreHeldItem(event.getEntity(), session);
                } else {
                    finish(event.getEntity(), session);
                }
                return;
            }
            replaceTemporaryBookDrop(event, session);
            if (!wasPendingRestore) {
                end(session, playerId);
            }
        }
    }

    void shutdown() {
        for (Map.Entry<UUID, EditSession> entry : Map.copyOf(sessions).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                finish(player, entry.getValue());
            }
        }
        for (Map.Entry<UUID, EditSession> entry : Map.copyOf(pendingRestores).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                restoreHeldItem(player, entry.getValue());
            }
        }
        sessions.clear();
        pendingRestores.clear();
        locks.clear();
    }

    private void openEditor(Player player, EditSession session) {
        if (sessions.get(player.getUniqueId()) != session) {
            return;
        }
        try {
            // The client opens an editable book from its selected-slot copy. Ensure its copy is
            // refreshed before the open-book packet, otherwise rapid consecutive edits can show
            // the prior version even though the lectern has the current one.
            player.updateInventory();
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            ClassLoader loader = handle.getClass().getClassLoader();
            Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack", true, loader);
            Class<?> nmsItemStack = Class.forName("net.minecraft.world.item.ItemStack", true, loader);
            Class<?> interactionHand = Class.forName("net.minecraft.world.InteractionHand", true, loader);
            Object nmsBook = craftItemStack.getMethod("asNMSCopy", ItemStack.class).invoke(null, session.originalBook);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object mainHand = Enum.valueOf((Class<? extends Enum>) interactionHand.asSubclass(Enum.class), "MAIN_HAND");
            Method openItemGui = findOpenItemGui(handle.getClass(), nmsItemStack, interactionHand);
            openItemGui.invoke(handle, nmsBook, mainHand);
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().warning("Could not open the writable-book editor: " + exception.getMessage());
            player.sendMessage("The server could not open the book editor.");
            finish(player, session);
        }
    }

    private static Method findOpenItemGui(Class<?> type, Class<?> itemStack, Class<?> interactionHand)
            throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals("openItemGui") && method.getParameterCount() == 2
                    && method.getParameterTypes()[0].isAssignableFrom(itemStack)
                    && method.getParameterTypes()[1].isAssignableFrom(interactionHand)) {
                return method;
            }
        }
        throw new NoSuchMethodException("ServerPlayer.openItemGui(ItemStack, InteractionHand)");
    }

    private void finishIfCurrent(Player player, EditSession session) {
        if (sessions.get(player.getUniqueId()) == session) {
            player.sendMessage("Lectern book editing timed out; changes were not saved.");
            finish(player, session);
        }
    }

    private void finish(Player player, EditSession session) {
        if (end(session, player.getUniqueId())) {
            restoreHeldItem(player, session);
        }
    }

    private void finishAfterBookEdit(Player player, EditSession session) {
        if (end(session, player.getUniqueId())) {
            // Paper finishes processing the incoming edit packet after event listeners return.
            // Restoring in the next tick prevents that processing from overwriting the player's
            // original selected item with the temporary book.
            pendingRestores.put(player.getUniqueId(), session);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (pendingRestores.remove(player.getUniqueId(), session)) {
                    restoreHeldItem(player, session);
                }
            });
        }
    }

    private boolean end(EditSession session, UUID playerId) {
        if (!sessions.remove(playerId, session)) {
            return false;
        }
        locks.remove(session.location, playerId);
        return true;
    }

    private static void restoreHeldItem(Player player, EditSession session) {
        if (player.isOnline()) {
            player.getInventory().setItem(session.heldSlot, copy(session.heldItem));
            player.updateInventory();
        }
    }

    private boolean finishIfEditing(Player player) {
        EditSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return false;
        }
        finish(player, session);
        return true;
    }

    private void restorePending(Player player) {
        EditSession session = pendingRestores.remove(player.getUniqueId());
        if (session != null) {
            restoreHeldItem(player, session);
        }
    }

    private static void replaceTemporaryBookDrop(PlayerDeathEvent event, EditSession session) {
        for (int index = 0; index < event.getDrops().size(); index++) {
            if (sameBook(event.getDrops().get(index), session.originalBook)) {
                if (session.heldItem == null) {
                    event.getDrops().remove(index);
                } else {
                    event.getDrops().set(index, copy(session.heldItem));
                }
                return;
            }
        }
    }

    private static boolean sameBook(ItemStack first, ItemStack second) {
        return first != null && second != null && first.isSimilar(second);
    }

    private static LecternInventory lecternInventory(Lectern lectern) {
        return (LecternInventory) lectern.getInventory();
    }

    private static ItemStack copy(ItemStack item) {
        return item == null ? null : item.clone();
    }

    private record EditSession(LecternLocation location, ItemStack originalBook, ItemStack heldItem, int heldSlot) {
    }

    private record LecternLocation(UUID world, int x, int y, int z) {
        private static LecternLocation of(Block block) {
            return new LecternLocation(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        private Block block() {
            World loadedWorld = Bukkit.getWorld(world);
            return loadedWorld == null ? null : loadedWorld.getBlockAt(x, y, z);
        }
    }
}
