package local.fastminecarts;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.Consumable;
import io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareGrindstoneEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class CraftCommandWorkbench implements Listener {
    static final String REQUIRED_NAME = "craft-command-workbench";
    static final int MAX_USES = 64;

    private static final String USES_LORE_PREFIX = "Uses remaining: ";

    private final NamespacedKey remainingUsesKey;
    private final NamespacedKey identityKey;
    private final MinecraftCustomUtilitiesPlugin plugin;
    private final Map<UUID, PortableSession> portableSessions = new HashMap<>();

    CraftCommandWorkbench(MinecraftCustomUtilitiesPlugin plugin) {
        this.plugin = plugin;
        remainingUsesKey = new NamespacedKey(plugin, "craft-command-workbench-remaining-uses");
        identityKey = new NamespacedKey(plugin, "craft-command-workbench-id");
    }

    Bench findOrInitialize(Player player) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        boolean foundStack = false;
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack item = storage[slot];
            if (!hasRequiredName(item)) {
                continue;
            }
            if (item.getAmount() != 1) {
                foundStack = true;
                continue;
            }

            int remaining = remainingUses(item);
            if (remaining < 0) {
                remaining = MAX_USES;
            }
            remaining = Math.clamp(remaining, 0, MAX_USES);
            String identity = ensureIdentity(item);
            applyState(item, remaining);
            player.getInventory().setItem(slot, item);
            return new Bench(slot, remaining, identity);
        }

        if (foundStack) {
            player.sendMessage("Your " + REQUIRED_NAME
                    + " must be a single item. Split the stack before using /craft.");
        } else {
            player.sendMessage("You need a crafting table named exactly " + REQUIRED_NAME
                    + " in your main inventory to use /craft.");
        }
        return null;
    }

    int remainingUses(ItemStack item) {
        if (item == null || item.getType() != Material.CRAFTING_TABLE || !item.hasItemMeta()) {
            return -1;
        }
        Integer value = item.getItemMeta().getPersistentDataContainer()
                .get(remainingUsesKey, PersistentDataType.INTEGER);
        return value == null ? -1 : value;
    }

    void applyState(ItemStack item, int remaining) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(remainingUsesKey, PersistentDataType.INTEGER, remaining);
        meta.setMaxStackSize(1);

        List<Component> lore = meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.removeIf(line -> PlainTextComponentSerializer.plainText().serialize(line)
                .startsWith(USES_LORE_PREFIX));
        lore.add(Component.text(USES_LORE_PREFIX + remaining + "/" + MAX_USES));
        meta.lore(lore);

        if (meta instanceof Damageable damageable) {
            damageable.setMaxDamage(MAX_USES);
            damageable.setDamage(MAX_USES - remaining);
        }
        item.setItemMeta(meta);
        item.setData(DataComponentTypes.CONSUMABLE, Consumable.consumable()
                .consumeSeconds(3600.0F)
                .animation(ItemUseAnimation.NONE)
                .hasConsumeParticles(false));
    }

    private String ensureIdentity(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        String identity = meta.getPersistentDataContainer().get(identityKey, PersistentDataType.STRING);
        if (identity == null) {
            identity = UUID.randomUUID().toString();
            meta.getPersistentDataContainer().set(identityKey, PersistentDataType.STRING, identity);
            item.setItemMeta(meta);
        }
        return identity;
    }

    private String identity(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(identityKey, PersistentDataType.STRING);
    }

    boolean isProtectedWorkbench(ItemStack item) {
        return remainingUses(item) >= 0 || hasRequiredName(item);
    }

    private static boolean hasRequiredName(ItemStack item) {
        if (item == null || item.getType() != Material.CRAFTING_TABLE || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.hasCustomName()
                && REQUIRED_NAME.equals(PlainTextComponentSerializer.plainText().serialize(meta.customName()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isProtectedWorkbench(event.getItemInHand())) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage("The " + REQUIRED_NAME
                + " cannot be placed. Use it with /craft <item> [count].");
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || !hasRequiredName(event.getItem())) {
            return;
        }
        event.setCancelled(true);

        Player player = event.getPlayer();
        int slot = player.getInventory().getHeldItemSlot();
        ItemStack item = player.getInventory().getItem(slot);
        if (item == null || item.getAmount() != 1) {
            player.sendMessage("Your " + REQUIRED_NAME
                    + " must be a single item. Split the stack before using it.");
            return;
        }

        int remaining = remainingUses(item);
        if (remaining < 0) {
            remaining = MAX_USES;
        }
        if (remaining == 0) {
            player.sendMessage("That command workbench has no uses remaining.");
            return;
        }
        String identity = ensureIdentity(item);
        applyState(item, remaining);
        player.getInventory().setItem(slot, item);

        InventoryView view = player.openWorkbench(null, true);
        portableSessions.put(player.getUniqueId(), new PortableSession(view.getTopInventory(), identity));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        for (ItemStack item : event.getPlayer().getInventory().getStorageContents()) {
            initializeNamedSingleton(item);
        }
    }

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        initializeNamedSingleton(event.getPlayer().getInventory().getItem(event.getNewSlot()));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortableCraft(CraftItemEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        PortableSession session = portableSessions.get(player.getUniqueId());
        if (session == null || event.getView().getTopInventory() != session.inventory()) {
            return;
        }
        if (event.getAction() == InventoryAction.NOTHING
                || event.getAction() == InventoryAction.UNKNOWN) {
            return;
        }

        LocatedBench located = findByIdentity(player, session.identity());
        if (located == null || !hasRequiredName(located.item())) {
            event.setCancelled(true);
            player.sendMessage("The command workbench must remain in your main inventory while crafting.");
            player.closeInventory();
            return;
        }

        int remaining = remainingUses(located.item());
        if (remaining <= 0) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        remaining--;
        if (remaining == 0) {
            player.getInventory().setItem(located.slot(), null);
            player.sendMessage("The command workbench broke.");
            plugin.getServer().getScheduler().runTask(plugin, (Runnable) player::closeInventory);
        } else {
            applyState(located.item(), remaining);
            player.getInventory().setItem(located.slot(), located.item());
            player.sendMessage("Workbench uses remaining: " + remaining + "/" + MAX_USES + ".");
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        PortableSession session = portableSessions.get(event.getPlayer().getUniqueId());
        if (session != null && event.getView().getTopInventory() == session.inventory()) {
            portableSessions.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        portableSessions.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (containsProtectedWorkbench(event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        ItemStack first = event.getInventory().getFirstItem();
        ItemStack second = event.getInventory().getSecondItem();
        if (second != null && !second.getType().isAir()
                && (isProtectedWorkbench(first) || isProtectedWorkbench(second))) {
            event.setResult(null);
            return;
        }

        ItemStack result = event.getResult();
        if (result != null && result.getAmount() == 1 && hasRequiredName(result)) {
            initializeNamedSingleton(result);
            event.setResult(result);
        }
    }

    @EventHandler
    public void onPrepareGrindstone(PrepareGrindstoneEvent event) {
        if (isProtectedWorkbench(event.getInventory().getUpperItem())
                || isProtectedWorkbench(event.getInventory().getLowerItem())) {
            event.setResult(null);
        }
    }

    private boolean containsProtectedWorkbench(ItemStack[] items) {
        for (ItemStack item : items) {
            if (isProtectedWorkbench(item)) {
                return true;
            }
        }
        return false;
    }

    private LocatedBench findByIdentity(Player player, String wantedIdentity) {
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int slot = 0; slot < storage.length; slot++) {
            ItemStack item = storage[slot];
            if (wantedIdentity.equals(identity(item))) {
                return new LocatedBench(slot, item);
            }
        }
        return null;
    }

    private void initializeNamedSingleton(ItemStack item) {
        if (item == null || item.getAmount() != 1 || !hasRequiredName(item)) {
            return;
        }
        int remaining = remainingUses(item);
        ensureIdentity(item);
        applyState(item, remaining < 0 ? MAX_USES : Math.clamp(remaining, 0, MAX_USES));
    }

    record Bench(int slot, int remainingUses, String identity) {
    }

    private record PortableSession(Inventory inventory, String identity) {
    }

    private record LocatedBench(int slot, ItemStack item) {
    }
}
