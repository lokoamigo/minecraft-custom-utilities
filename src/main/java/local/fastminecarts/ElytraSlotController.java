package local.fastminecarts;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.HappyGhast;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

final class ElytraSlotController implements Listener {
    private static final int SLOT = 4;
    private static final int STATUS_SEGMENTS = 4;
    private static final Component TITLE = Component.text("Elytra Slot");
    private static final NamespacedKey LEGACY_STORED_ELYTRA_KEY =
            Objects.requireNonNull(NamespacedKey.fromString("fastminecarts:elytra_slot"));

    private final MinecraftCustomUtilitiesPlugin plugin;
    private final NamespacedKey storedElytraKey;
    private final Set<UUID> forcedGlideStops = new HashSet<>();
    private final Set<UUID> jumpHeld = new HashSet<>();
    private final Set<UUID> statusVisible = new HashSet<>();

    ElytraSlotController(MinecraftCustomUtilitiesPlugin plugin) {
        this.plugin = plugin;
        this.storedElytraKey = new NamespacedKey(plugin, "elytra_slot");

        Bukkit.getScheduler().runTaskTimer(plugin, this::tickGlidingPlayers, 20L, 20L);
    }

    void open(Player player) {
        ElytraInventory holder = new ElytraInventory(player.getUniqueId());
        holder.inventory.setItem(SLOT, load(player));
        player.openInventory(holder.inventory);
    }

    @EventHandler
    public void onInput(PlayerInputEvent event) {
        Player player = event.getPlayer();
        boolean wasJumping = jumpHeld.contains(player.getUniqueId());
        if (event.getInput().isJump()) {
            jumpHeld.add(player.getUniqueId());
        } else {
            jumpHeld.remove(player.getUniqueId());
        }
        if (wasJumping || !event.getInput().isJump() || player.isGliding()) {
            return;
        }
        if (canGlide(player)) {
            player.setGliding(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        forcedGlideStops.remove(playerId);
        jumpHeld.remove(playerId);
        statusVisible.remove(playerId);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onToggleGlide(EntityToggleGlideEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.isGliding()) {
            return;
        }
        if (forcedGlideStops.contains(player.getUniqueId())) {
            hideStatus(player);
            return;
        }
        if (canGlide(player)) {
            event.setCancelled(true);
            return;
        }
        hideStatus(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleInteract(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof Vehicle) {
            stopGlidingForVehicle(event.getPlayer());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            stopGlidingForVehicle(player);
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

    @EventHandler(priority = EventPriority.HIGH)
    public void onExperience(PlayerExpChangeEvent event) {
        if (!(event.getSource() instanceof ExperienceOrb experienceOrb) || event.getAmount() <= 0) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack elytra = currentItem(player);
        if (isEmpty(elytra) || !elytra.containsEnchantment(Enchantment.MENDING)
                || !(elytra.getItemMeta() instanceof Damageable damageable)
                || damageable.getDamage() <= 0) {
            return;
        }

        int availableExperience = event.getAmount();
        int maximumRepair = availableExperience > Integer.MAX_VALUE / 2
                ? Integer.MAX_VALUE : availableExperience * 2;
        int offeredRepair = Math.min(damageable.getDamage(), maximumRepair);
        int offeredConsumption = offeredRepair / 2;
        PlayerItemMendEvent mendEvent = new PlayerItemMendEvent(player, elytra, EquipmentSlot.CHEST,
                experienceOrb, offeredRepair, offeredConsumption);
        Bukkit.getPluginManager().callEvent(mendEvent);
        if (mendEvent.isCancelled()) {
            return;
        }

        int repair = Math.max(0, Math.min(damageable.getDamage(),
                Math.min(maximumRepair, mendEvent.getRepairAmount())));
        if (repair == 0) {
            return;
        }
        int consumedExperience = repair / 2;
        damageable.setDamage(damageable.getDamage() - repair);
        elytra.setItemMeta(damageable);
        updateStoredItem(player, elytra);
        event.setAmount(availableExperience - consumedExperience);
    }

    private void tickGlidingPlayers() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isGliding()) {
                hideStatus(player);
                continue;
            }
            if (hasVanillaElytraEquipped(player)) {
                showStatus(player, player.getInventory().getItem(EquipmentSlot.CHEST));
                statusVisible.add(player.getUniqueId());
                continue;
            }
            if (!canGlide(player)) {
                player.setGliding(false);
                hideStatus(player);
                continue;
            }

            ItemStack damaged = currentItem(player).damage(1, player);
            if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof ElytraInventory holder
                    && holder.owner.equals(player.getUniqueId())) {
                holder.inventory.setItem(SLOT, damaged);
            }
            save(player, damaged);
            showStatus(player, damaged);
            statusVisible.add(player.getUniqueId());
            if (!hasUsableElytra(player)) {
                player.setGliding(false);
                hideStatus(player);
            }
        }
    }

    private boolean canGlide(Player player) {
        return !hasVanillaElytraEquipped(player)
                && !isUsingVehicle(player)
                && !player.getAllowFlight()
                && !player.isFlying()
                && !player.isOnGround()
                && !player.isSwimming()
                && !player.isClimbing()
                && hasUsableElytra(player);
    }

    private static boolean isUsingVehicle(Player player) {
        if (player.isInsideVehicle()) {
            return true;
        }

        // Paper 26.3 does not expose Happy Ghast attachments through
        // Player#getVehicle(), so use the same narrow positional fallback as
        // GhastController until the attachment is available through the API.
        return player.getWorld().getNearbyEntities(player.getLocation(), 1.0, 1.0, 1.0,
                        entity -> entity instanceof HappyGhast)
                .stream()
                .anyMatch(entity -> entity.getLocation().distanceSquared(player.getLocation()) < 1.0);
    }

    private static boolean hasVanillaElytraEquipped(Player player) {
        ItemStack chestItem = player.getInventory().getItem(EquipmentSlot.CHEST);
        return !isEmpty(chestItem) && chestItem.getType() == Material.ELYTRA;
    }

    void shutdown() {
        forcedGlideStops.clear();
        jumpHeld.clear();
        statusVisible.clear();
    }

    private void stopGlidingForVehicle(Player player) {
        if (!player.isGliding()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        forcedGlideStops.add(playerId);
        try {
            player.setGliding(false);
        } finally {
            forcedGlideStops.remove(playerId);
        }
        hideStatus(player);
    }

    private void hideStatus(Player player) {
        if (statusVisible.remove(player.getUniqueId())) {
            player.sendActionBar(Component.empty());
        }
    }

    private static void showStatus(Player player, ItemStack item) {
        Damageable damageable = (Damageable) item.getItemMeta();
        int maximum = damageable.hasMaxDamage() ? damageable.getMaxDamage() : item.getType().getMaxDurability();
        int remaining = Math.max(0, maximum - damageable.getDamage());
        int percentage = maximum == 0 ? 0 : (int) ((long) remaining * 100 / maximum);
        int filledSegments = maximum == 0 ? 0
                : Math.min(STATUS_SEGMENTS,
                        (int) (((long) remaining * STATUS_SEGMENTS + maximum - 1) / maximum));
        NamedTextColor statusColor = percentage > 50 ? NamedTextColor.GREEN
                : percentage >= 25 ? NamedTextColor.YELLOW : NamedTextColor.RED;

        Component status = Component.text("Elytra  ", NamedTextColor.GRAY)
                .append(Component.text("█".repeat(filledSegments), statusColor))
                .append(Component.text("░".repeat(STATUS_SEGMENTS - filledSegments), NamedTextColor.DARK_GRAY))
                .append(Component.text("  " + percentage + "% (" + remaining + "/" + maximum + ")",
                        statusColor));
        player.sendActionBar(status);
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

    private void updateStoredItem(Player player, ItemStack item) {
        if (player.getOpenInventory().getTopInventory().getHolder(false) instanceof ElytraInventory holder
                && holder.owner.equals(player.getUniqueId())) {
            holder.inventory.setItem(SLOT, item);
        }
        save(player, item);
    }

    private ItemStack load(Player player) {
        byte[] bytes = player.getPersistentDataContainer().get(storedElytraKey, PersistentDataType.BYTE_ARRAY);
        boolean legacy = false;
        if (bytes == null || bytes.length == 0) {
            bytes = player.getPersistentDataContainer().get(LEGACY_STORED_ELYTRA_KEY,
                    PersistentDataType.BYTE_ARRAY);
            legacy = bytes != null && bytes.length > 0;
        }
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            ItemStack item = ItemStack.deserializeBytes(bytes);
            if (item.getType() != Material.ELYTRA) {
                return null;
            }
            if (legacy) {
                player.getPersistentDataContainer().set(storedElytraKey, PersistentDataType.BYTE_ARRAY, bytes);
                player.getPersistentDataContainer().remove(LEGACY_STORED_ELYTRA_KEY);
            }
            return item;
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not load the Elytra slot for " + player.getName() + ": " + exception.getMessage());
            return null;
        }
    }

    private void save(Player player, ItemStack item) {
        if (isEmpty(item)) {
            player.getPersistentDataContainer().remove(storedElytraKey);
        } else if (item.getType() == Material.ELYTRA) {
            player.getPersistentDataContainer().set(storedElytraKey, PersistentDataType.BYTE_ARRAY, item.serializeAsBytes());
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
