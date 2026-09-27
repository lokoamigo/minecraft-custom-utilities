package local.fastminecarts;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class ElytraSlotController implements Listener {
    private static final int SLOT = 4;
    private static final Component TITLE = Component.text("Elytra Slot");

    private final FastMinecartsPlugin plugin;
    private final NamespacedKey storedElytraKey;
    private final Set<UUID> temporarilyFlightEnabled = new HashSet<>();

    ElytraSlotController(FastMinecartsPlugin plugin) {
        this.plugin = plugin;
        this.storedElytraKey = new NamespacedKey(plugin, "elytra_slot");

        Bukkit.getScheduler().runTaskTimer(plugin, this::tickGlidingPlayers, 20L, 20L);
    }

    void open(Player player) {
        ElytraInventory holder = new ElytraInventory(player.getUniqueId());
        holder.inventory.setItem(SLOT, load(player));
        player.openInventory(holder.inventory);
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.isSwimming() || player.isClimbing()) {
            disarmFlightInput(player);
            return;
        }
        if (!player.isGliding() && hasUsableElytra(player)) {
            armFlightInput(player);
        }
        if (player.isOnGround()) {
            return;
        }
        if (!player.isGliding()
                && player.isSneaking()
                && player.getVelocity().getY() < 0.0
                && canGlide(player)) {
            player.setGliding(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (!temporarilyFlightEnabled.remove(player.getUniqueId()) || !hasUsableElytra(player)) {
            return;
        }
        event.setCancelled(true);
        player.setFlying(false);
        player.setAllowFlight(false);
        if (canGlide(player)) {
            player.setGliding(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        syncFlightInput(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> syncFlightInput(event.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        disarmFlightInput(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.isGliding()) {
            return;
        }
        if (!player.isOnGround() && !player.isSwimming() && !player.isClimbing() && hasUsableElytra(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof ElytraInventory holder)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < event.getView().getTopInventory().getSize() && rawSlot != SLOT) {
            event.setCancelled(true);
            return;
        }

        if (rawSlot == SLOT && !isEmpty(event.getCursor()) && event.getCursor().getType() != Material.ELYTRA) {
            event.setCancelled(true);
            return;
        }

        if (event.isShiftClick() && rawSlot >= event.getView().getTopInventory().getSize()) {
            if (event.getCurrentItem() == null || event.getCurrentItem().getType() != Material.ELYTRA
                    || !isEmpty(holder.inventory.getItem(SLOT))) {
                event.setCancelled(true);
                return;
            }
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack item = holder.inventory.getItem(SLOT);
            if (!isEmpty(item) && item.getType() != Material.ELYTRA) {
                holder.inventory.setItem(SLOT, null);
                player.getInventory().addItem(item).values().forEach(leftover ->
                        player.getWorld().dropItemNaturally(player.getLocation(), leftover));
            }
            save(player, holder.inventory.getItem(SLOT));
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof ElytraInventory holder)) {
            return;
        }
        boolean touchesOtherTopSlot = event.getRawSlots().stream()
                .anyMatch(slot -> slot < holder.inventory.getSize() && slot != SLOT);
        ItemStack newItem = event.getNewItems().get(SLOT);
        if (touchesOtherTopSlot || (newItem != null && newItem.getType() != Material.ELYTRA)) {
            event.setCancelled(true);
            return;
        }
        if (event.getWhoClicked() instanceof Player player) {
            Bukkit.getScheduler().runTask(plugin, () -> save(player, holder.inventory.getItem(SLOT)));
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof ElytraInventory holder
                && event.getPlayer() instanceof Player player
                && holder.owner.equals(player.getUniqueId())) {
            save(player, holder.inventory.getItem(SLOT));
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (event.getKeepInventory()) {
            return;
        }
        ItemStack elytra = currentItem(player);
        if (!isEmpty(elytra)) {
            event.getDrops().add(elytra);
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof ElytraInventory holder
                    && holder.owner.equals(player.getUniqueId())) {
                holder.inventory.setItem(SLOT, null);
            }
            save(player, null);
        }
    }

    private void tickGlidingPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isGliding()) {
                continue;
            }
            if (!canGlide(player)) {
                player.setGliding(false);
                continue;
            }

            ItemStack damaged = currentItem(player).damage(1, player);
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof ElytraInventory holder
                    && holder.owner.equals(player.getUniqueId())) {
                holder.inventory.setItem(SLOT, damaged);
            }
            save(player, damaged);
            if (!hasUsableElytra(player)) {
                player.setGliding(false);
            }
        }
    }

    private boolean canGlide(Player player) {
        return !player.isOnGround() && !player.isSwimming() && !player.isClimbing() && hasUsableElytra(player);
    }

    void shutdown() {
        for (UUID playerId : Set.copyOf(temporarilyFlightEnabled)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                disarmFlightInput(player);
            }
        }
    }

    private void armFlightInput(Player player) {
        GameMode gameMode = player.getGameMode();
        if ((gameMode == GameMode.SURVIVAL || gameMode == GameMode.ADVENTURE)
                && !player.getAllowFlight()) {
            temporarilyFlightEnabled.add(player.getUniqueId());
            player.setAllowFlight(true);
        }
    }

    private void disarmFlightInput(Player player) {
        if (temporarilyFlightEnabled.remove(player.getUniqueId())) {
            player.setFlying(false);
            player.setAllowFlight(false);
        }
    }

    private void syncFlightInput(Player player) {
        if (!player.isGliding() && !player.isSwimming() && !player.isClimbing()
                && hasUsableElytra(player)) {
            armFlightInput(player);
        } else {
            disarmFlightInput(player);
        }
    }

    private boolean hasUsableElytra(Player player) {
        ItemStack item = currentItem(player);
        if (isEmpty(item) || item.getType() != Material.ELYTRA) {
            return false;
        }
        Damageable damageable = (Damageable) item.getItemMeta();
        int maximum = damageable.hasMaxDamage() ? damageable.getMaxDamage() : item.getType().getMaxDurability();
        return damageable.getDamage() < maximum - 1;
    }

    private ItemStack currentItem(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof ElytraInventory holder
                && holder.owner.equals(player.getUniqueId())) {
            return holder.inventory.getItem(SLOT);
        }
        return load(player);
    }

    private ItemStack load(Player player) {
        byte[] bytes = player.getPersistentDataContainer().get(storedElytraKey, PersistentDataType.BYTE_ARRAY);
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            ItemStack item = ItemStack.deserializeBytes(bytes);
            return item.getType() == Material.ELYTRA ? item : null;
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not load the Elytra slot for " + player.getName() + ": " + exception.getMessage());
            return null;
        }
    }

    private void save(Player player, ItemStack item) {
        if (isEmpty(item)) {
            player.getPersistentDataContainer().remove(storedElytraKey);
            disarmFlightInput(player);
        } else if (item.getType() == Material.ELYTRA) {
            player.getPersistentDataContainer().set(storedElytraKey, PersistentDataType.BYTE_ARRAY, item.serializeAsBytes());
            syncFlightInput(player);
        }
    }

    private static boolean isEmpty(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() == 0;
    }

    private static final class ElytraInventory implements InventoryHolder {
        private final UUID owner;
        private final Inventory inventory;

        private ElytraInventory(UUID owner) {
            this.owner = owner;
            this.inventory = Bukkit.createInventory(this, 9, TITLE);

            ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
            Arrays.stream(new int[]{0, 1, 2, 3, 5, 6, 7, 8}).forEach(slot -> inventory.setItem(slot, filler));
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
