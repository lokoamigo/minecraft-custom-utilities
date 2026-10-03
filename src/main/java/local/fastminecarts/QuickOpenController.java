package local.fastminecarts;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class QuickOpenController implements Listener {
    private static final int OFFHAND_SLOT = 40;

    private final MinecraftCustomUtilitiesPlugin plugin;
    private final NamespacedKey sessionKey;
    private final Map<UUID, Session> sessions = new HashMap<>();

    QuickOpenController(MinecraftCustomUtilitiesPlugin plugin) {
        this.plugin = plugin;
        sessionKey = new NamespacedKey(plugin, "quickopen_session");
    }

    void open(Player player) {
        if (sessions.containsKey(player.getUniqueId())) {
            player.closeInventory();
        }

        int sourceSlot = player.getInventory().getHeldItemSlot();
        ItemStack source = player.getInventory().getItem(sourceSlot);
        if (!isShulker(source)) {
            sourceSlot = OFFHAND_SLOT;
            source = player.getInventory().getItemInOffHand();
        }
        if (!isShulker(source)) {
            player.sendMessage("Hold a shulker box in your main hand or offhand.");
            return;
        }

        String token = UUID.randomUUID().toString();
        ItemStack marked = source.clone();
        BlockStateMeta meta = (BlockStateMeta) marked.getItemMeta();
        meta.getPersistentDataContainer().set(sessionKey, PersistentDataType.STRING, token);
        marked.setItemMeta(meta);
        player.getInventory().setItem(sourceSlot, marked);

        ShulkerBox shulker = shulkerState(marked);
        if (shulker == null) {
            removeMarker(marked);
            player.getInventory().setItem(sourceSlot, marked);
            player.sendMessage("That shulker box could not be opened.");
            return;
        }

        InventoryView view = player.openInventory(shulker.getSnapshotInventory());
        Session session = new Session(token, view.getTopInventory());
        sessions.put(player.getUniqueId(), session);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void lockSourceItem(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Session session = openSession(player, event.getView().getTopInventory());
        if (session == null) {
            return;
        }

        if (hasToken(event.getCurrentItem(), session.token())
                || hasToken(event.getCursor(), session.token())
                || event.getHotbarButton() >= 0
                && hasToken(player.getInventory().getItem(event.getHotbarButton()), session.token())
                || hasToken(player.getInventory().getItemInOffHand(), session.token())
                && event.getClick().isKeyboardClick()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void synchronizeAfterClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && openSession(player, event.getView().getTopInventory()) != null) {
            scheduleSync(player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void synchronizeAfterDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && openSession(player, event.getView().getTopInventory()) != null) {
            scheduleSync(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void preventHandSwap(PlayerSwapHandItemsEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && (hasToken(event.getMainHandItem(), session.token())
                || hasToken(event.getOffHandItem(), session.token()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void preventSlotChange(PlayerItemHeldEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && hasToken(
                event.getPlayer().getInventory().getItem(event.getPreviousSlot()), session.token())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void preventSourceDrop(PlayerDropItemEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        Item dropped = event.getItemDrop();
        if (session != null && hasToken(dropped.getItemStack(), session.token())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void close(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Session session = openSession(player, event.getView().getTopInventory());
        if (session != null) {
            finish(player, session);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            finish(event.getPlayer(), session);
        }
    }

    void shutdown() {
        for (UUID playerId : new ArrayList<>(sessions.keySet())) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                player.closeInventory();
                Session session = sessions.get(playerId);
                if (session != null) {
                    finish(player, session);
                }
            }
        }
        sessions.clear();
    }

    private void scheduleSync(Player player) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            Session session = sessions.get(player.getUniqueId());
            if (session != null && player.getOpenInventory().getTopInventory() == session.inventory()) {
                synchronize(player, session, false);
            }
        });
    }

    private void finish(Player player, Session session) {
        sessions.remove(player.getUniqueId(), session);
        if (!synchronize(player, session, true)) {
            plugin.getLogger().warning("Could not find the source shulker for " + player.getName()
                    + " while closing /quickopen; its contents were not overwritten.");
            player.sendMessage("The source shulker moved unexpectedly; it was not overwritten.");
        }
    }

    private boolean synchronize(Player player, Session session, boolean removeToken) {
        int slot = findSourceSlot(player, session.token());
        if (slot < 0) {
            return false;
        }

        ItemStack source = player.getInventory().getItem(slot);
        if (!(source != null && source.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox shulker)) {
            return false;
        }
        shulker.getSnapshotInventory().setContents(cloneContents(session.inventory().getContents()));
        meta.setBlockState(shulker);
        if (removeToken) {
            meta.getPersistentDataContainer().remove(sessionKey);
        }
        source.setItemMeta(meta);
        player.getInventory().setItem(slot, source);
        return true;
    }

    private int findSourceSlot(Player player, String token) {
        for (int slot = 0; slot < player.getInventory().getStorageContents().length; slot++) {
            if (hasToken(player.getInventory().getItem(slot), token)) {
                return slot;
            }
        }
        return hasToken(player.getInventory().getItemInOffHand(), token) ? OFFHAND_SLOT : -1;
    }

    private Session openSession(Player player, Inventory topInventory) {
        Session session = sessions.get(player.getUniqueId());
        return session != null && session.inventory() == topInventory ? session : null;
    }

    private boolean hasToken(ItemStack item, String token) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        String value = item.getItemMeta().getPersistentDataContainer()
                .get(sessionKey, PersistentDataType.STRING);
        return token.equals(value);
    }

    private void removeMarker(ItemStack item) {
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
        meta.getPersistentDataContainer().remove(sessionKey);
        item.setItemMeta(meta);
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int slot = 0; slot < contents.length; slot++) {
            copy[slot] = contents[slot] == null ? null : contents[slot].clone();
        }
        return copy;
    }

    private static boolean isShulker(ItemStack item) {
        return shulkerState(item) != null;
    }

    private static ShulkerBox shulkerState(ItemStack item) {
        if (item == null || item.isEmpty()
                || !(item.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox shulker)) {
            return null;
        }
        return shulker;
    }

    private record Session(String token, Inventory inventory) {
    }
}
