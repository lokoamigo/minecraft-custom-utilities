package local.fastminecarts;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ShopkeeperController implements Listener {
    static final String TOOL_NAME = "shopkeeper";
    static final double MIN_BOUNDARY_RADIUS = 1.0;
    static final double MAX_BOUNDARY_RADIUS = 32.0;
    static final double MIN_MOVEMENT_SPEED = 0.1;
    static final double MAX_MOVEMENT_SPEED = 2.0;
    static final double MIN_MAX_HEALTH = 1.0;
    static final double MAX_MAX_HEALTH = 1024.0;
    private static final double DEFAULT_BOUNDARY_RADIUS = 2.5;
    private static final double DEFAULT_MOVEMENT_SPEED = 0.75;
    private static final int SHOP_PATHFINDING_TIMEOUT_CHECKS = 20;
    private static final double DEFAULT_MAX_HEALTH = 40.0;
    private static final double SHOPKEEPER_ARMOR = 12.0;
    private static final double SHOPKEEPER_ARMOR_TOUGHNESS = 4.0;
    private static final double THORNS_DAMAGE = 4.0;

    private final MinecraftCustomUtilitiesPlugin plugin;
    private final NamespacedKey shopkeeperOwnerKey;
    private final NamespacedKey offerMaterialKey;
    private final File dataFile;
    private final Map<UUID, Shop> shops = new HashMap<>();
    private final Map<UUID, UUID> selectedShops = new HashMap<>();
    private final Map<UUID, Integer> boundaryReturnChecks = new HashMap<>();
    private final Map<UUID, Set<Material>> unavailableListingStates = new HashMap<>();

    ShopkeeperController(MinecraftCustomUtilitiesPlugin plugin) {
        this.plugin = plugin;
        shopkeeperOwnerKey = new NamespacedKey(plugin, "shopkeeper-owner");
        offerMaterialKey = new NamespacedKey(plugin, "shopkeeper-offer-material");
        dataFile = new File(plugin.getDataFolder(), "shopkeepers.yml");
        load();
        boolean migratedCenter = false;
        for (World world : Bukkit.getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (owner(villager) == null) {
                    continue;
                }
                Shop shop = shops.get(villager.getUniqueId());
                applyShopkeeperProtection(villager,
                        shop == null ? DEFAULT_MAX_HEALTH : shop.maxHealth);
                if (shop != null) {
                    applyShopkeeperMovement(villager, shop);
                    applyShopkeeperName(villager, shop);
                }
                if (shop != null && shop.center == null) {
                    shop.center = ShopCenter.from(villager.getLocation());
                    migratedCenter = true;
                }
            }
        }
        if (migratedCenter) {
            save();
        }
        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            enforceShopkeeperBoundaries();
            checkUnavailableListingTransitions();
        }, 10L, 10L);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVillagerInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Villager villager)) {
            return;
        }

        Player player = event.getPlayer();
        UUID owner = owner(villager);
        if (isShopkeeperTool(player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            if (owner != null && !owner.equals(player.getUniqueId())) {
                player.sendMessage("That shopkeeper belongs to another player.");
                return;
            }
            if (owner == null) {
                owner = player.getUniqueId();
                villager.getPersistentDataContainer().set(
                        shopkeeperOwnerKey, PersistentDataType.STRING, owner.toString());
                villager.customName(Component.text("Shopkeeper"));
                villager.setCustomNameVisible(true);
                villager.setPersistent(true);
                villager.setRecipes(List.of());
                Shop shop = new Shop(owner, ShopCenter.from(villager.getLocation()));
                shops.put(villager.getUniqueId(), shop);
                applyShopkeeperProtection(villager, shop.maxHealth);
                applyShopkeeperMovement(villager, shop);
                villager.setHealth(shop.maxHealth);
                save();
                player.sendMessage("Villager transformed into your shopkeeper.");
            } else {
                if (!shops.containsKey(villager.getUniqueId())) {
                    shops.put(villager.getUniqueId(),
                            new Shop(owner, ShopCenter.from(villager.getLocation())));
                    save();
                } else if (shops.get(villager.getUniqueId()).center == null) {
                    shops.get(villager.getUniqueId()).center = ShopCenter.from(villager.getLocation());
                    save();
                }
                applyShopkeeperProtection(villager, shops.get(villager.getUniqueId()).maxHealth);
                applyShopkeeperMovement(villager, shops.get(villager.getUniqueId()));
                player.sendMessage("Shopkeeper selected.");
            }
            selectedShops.put(player.getUniqueId(), villager.getUniqueId());
            player.sendMessage("Left-click chests with the shopkeeper shovel to link or unlink stock.");
            return;
        }

        if (owner != null) {
            event.setCancelled(true);
            openStore(player, villager.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChestInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null
                || !(event.getClickedBlock().getState() instanceof Chest chest)) {
            return;
        }

        Player player = event.getPlayer();
        Set<BlockLocation> locations = chestLocations(chest);
        List<Shop> linked = linkedShops(locations);
        if (event.getAction().isRightClick() && linked.stream()
                .anyMatch(shop -> !shop.owner.equals(player.getUniqueId()))) {
            event.setCancelled(true);
            player.sendMessage("That chest is locked while its shopkeeper is alive.");
            return;
        }
        if (!event.getAction().isLeftClick() || !isShopkeeperTool(event.getItem())) {
            return;
        }

        Shop shop = selectedOwnedShop(player);
        if (shop == null) {
            player.sendMessage("First right-click your shopkeeper with the shopkeeper shovel.");
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        if (shop.chests.removeAll(locations)) {
            player.sendMessage("Chest unlinked from the selected shopkeeper.");
        } else {
            shop.chests.addAll(locations);
            player.sendMessage("Chest linked to the selected shopkeeper.");
        }
        save();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLinkedChestBreak(BlockBreakEvent event) {
        if (!(event.getBlock().getState() instanceof Chest chest)) {
            return;
        }
        Set<BlockLocation> locations = chestLocations(chest);
        List<Shop> linked = linkedShops(locations);
        if (linked.isEmpty()) {
            return;
        }
        if (linked.stream().anyMatch(shop -> !shop.owner.equals(event.getPlayer().getUniqueId()))) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(
                    "That chest is protected while its shopkeeper is alive. Kill the shopkeeper to rob it.");
            return;
        }
        linked.forEach(shop -> shop.chests.removeAll(locations));
        save();
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedChest);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(this::isProtectedChest);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShopkeeperDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Villager villager)
                || (!shops.containsKey(villager.getUniqueId()) && owner(villager) == null)) {
            return;
        }
        LivingEntity attacker = attacker(event);
        if (attacker != null && !attacker.isDead()) {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (attacker.isValid() && !attacker.isDead()) {
                    applyThornsDamage(attacker);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShopkeeperDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Villager villager) || owner(villager) == null) {
            return;
        }
        UUID shopId = villager.getUniqueId();
        if (shops.remove(shopId) != null) {
            selectedShops.values().removeIf(shopId::equals);
            boundaryReturnChecks.remove(shopId);
            unavailableListingStates.remove(shopId);
            save();
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (org.bukkit.entity.Entity entity : event.getChunk().getEntities()) {
            if (entity instanceof Villager villager && owner(villager) != null) {
                Shop shop = shops.get(villager.getUniqueId());
                if (shop != null) {
                    applyShopkeeperProtection(villager, shop.maxHealth);
                    applyShopkeeperMovement(villager, shop);
                    applyShopkeeperName(villager, shop);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> shops.entrySet().stream()
                .filter(entry -> entry.getValue().owner.equals(player.getUniqueId()))
                .filter(entry -> isLiveShopkeeper(entry.getKey(), entry.getValue(), true))
                .forEach(entry -> {
                    Set<Material> unavailable = unavailableListings(entry.getValue(), true);
                    if (unavailable != null) {
                        unavailable.forEach(material -> notifyUnavailable(
                                player, entry.getValue(), material));
                    }
                }));
    }

    @EventHandler(ignoreCancelled = true)
    public void onStoreClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof StoreHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null) {
            return;
        }
        String key = clicked.getPersistentDataContainer().get(offerMaterialKey, PersistentDataType.STRING);
        Material material = key == null ? null : Material.matchMaterial(key);
        if (material != null) {
            buy(player, holder.shopId, material);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onStoreDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof StoreHolder) {
            event.setCancelled(true);
        }
    }

    boolean setPrice(Player player, Material material, int emeralds) {
        Shop shop = selectedOwnedShop(player);
        if (shop == null) {
            player.sendMessage("Select your shopkeeper by right-clicking it with the shopkeeper shovel.");
            return false;
        }
        shop.prices.put(material, emeralds);
        save();
        player.sendMessage(material.getKey() + " now costs " + emeralds + " emerald"
                + (emeralds == 1 ? "" : "s") + " each.");
        return true;
    }

    boolean removePrice(Player player, Material material) {
        Shop shop = selectedOwnedShop(player);
        if (shop == null) {
            player.sendMessage("Select your shopkeeper by right-clicking it with the shopkeeper shovel.");
            return false;
        }
        if (shop.prices.remove(material) == null) {
            player.sendMessage("That item did not have a configured price.");
            return false;
        }
        save();
        player.sendMessage("Removed the price for " + material.getKey() + ".");
        return true;
    }

    boolean setBoundaryRadius(Player player, double radius) {
        Shop shop = selectedOwnedShop(player);
        if (shop == null) {
            player.sendMessage("Select your shopkeeper by right-clicking it with the shopkeeper shovel.");
            return false;
        }
        shop.boundaryRadius = radius;
        UUID shopId = selectedShops.get(player.getUniqueId());
        boundaryReturnChecks.remove(shopId);
        org.bukkit.entity.Entity entity = Bukkit.getEntity(shopId);
        if (entity instanceof Villager villager) {
            applyShopkeeperMovement(villager, shop);
        }
        save();
        player.sendMessage(String.format(Locale.ROOT,
                "Shopkeeper radius set to %.2f blocks (%.2f × %.2f square).",
                radius, radius * 2.0, radius * 2.0));
        return true;
    }

    boolean setMovementSpeed(Player player, double speed) {
        Shop shop = selectedOwnedShop(player);
        if (shop == null) {
            player.sendMessage("Select your shopkeeper by right-clicking it with the shopkeeper shovel.");
            return false;
        }
        shop.movementSpeed = speed;
        UUID shopId = selectedShops.get(player.getUniqueId());
        org.bukkit.entity.Entity entity = Bukkit.getEntity(shopId);
        if (entity instanceof Villager villager) {
            applyShopkeeperMovement(villager, shop);
        }
        save();
        player.sendMessage(String.format(Locale.ROOT,
                "Shopkeeper movement speed set to %.2f×.", speed));
        return true;
    }

    boolean setMaxHealth(Player player, double maxHealth) {
        UUID shopId = selectedShops.get(player.getUniqueId());
        Shop shop = selectedOwnedShop(player);
        if (shop == null || shopId == null) {
            player.sendMessage("Select your shopkeeper by right-clicking it with the shopkeeper shovel.");
            return false;
        }
        org.bukkit.entity.Entity entity = Bukkit.getEntity(shopId);
        if (!(entity instanceof Villager villager) || !villager.isValid() || villager.isDead()) {
            player.sendMessage("The selected shopkeeper is not currently available.");
            return false;
        }
        shop.maxHealth = maxHealth;
        applyShopkeeperProtection(villager, maxHealth);
        villager.setHealth(maxHealth);
        save();
        player.sendMessage(String.format(Locale.ROOT,
                "Shopkeeper maximum health set to %.1f health points.", maxHealth));
        return true;
    }

    boolean setName(Player player, String name) {
        UUID shopId = selectedShops.get(player.getUniqueId());
        Shop shop = selectedOwnedShop(player);
        if (shop == null || shopId == null) {
            player.sendMessage("Select your shopkeeper by right-clicking it with the shopkeeper shovel.");
            return false;
        }
        shop.name = name;
        org.bukkit.entity.Entity entity = Bukkit.getEntity(shopId);
        if (entity instanceof Villager villager) {
            applyShopkeeperName(villager, shop);
        }
        save();
        player.sendMessage("Shopkeeper renamed to " + name + ".");
        return true;
    }

    void describe(Player player) {
        Shop shop = selectedOwnedShop(player);
        if (shop == null) {
            player.sendMessage("Select your shopkeeper by right-clicking it with the shopkeeper shovel.");
            return;
        }
        player.sendMessage("Selected shopkeeper: " + shop.chests.size() + " linked chest block(s), "
                + shop.prices.size() + " configured price(s).");
        player.sendMessage(String.format(Locale.ROOT,
                "Boundary radius: %.2f blocks; movement speed: %.2f×; maximum health: %.1f.",
                shop.boundaryRadius, shop.movementSpeed, shop.maxHealth));
        player.sendMessage("Name: " + shop.name + ".");
        shop.prices.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> player.sendMessage("- " + entry.getKey().getKey() + ": "
                        + entry.getValue() + " emerald(s)"));
    }

    private void openStore(Player player, UUID shopId) {
        Shop shop = shops.get(shopId);
        if (shop == null) {
            player.sendMessage("This shopkeeper has no shop data. Its owner must select it again.");
            return;
        }
        Map<Material, Stock> stock = stock(shop);
        StoreHolder holder = new StoreHolder(shopId);
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text(shop.name));
        holder.inventory = inventory;

        int slot = 0;
        for (Map.Entry<Material, Integer> entry : shop.prices.entrySet()) {
            Stock available = stock.get(entry.getKey());
            if (available == null || available.amount == 0 || slot == inventory.getSize()) {
                continue;
            }
            ItemStack display = available.example.clone();
            display.setAmount(1);
            ItemMeta meta = display.getItemMeta();
            List<Component> lore = meta.hasLore() && meta.lore() != null
                    ? new ArrayList<>(meta.lore()) : new ArrayList<>();
            lore.add(Component.text("Price: " + entry.getValue() + " emerald(s)"));
            lore.add(Component.text("In stock: " + available.amount));
            meta.lore(lore);
            display.setItemMeta(meta);
            display.editPersistentDataContainer(pdc -> pdc.set(
                    offerMaterialKey, PersistentDataType.STRING, entry.getKey().getKey().toString()));
            inventory.setItem(slot++, display);
        }
        player.openInventory(inventory);
    }

    private void buy(Player player, UUID shopId, Material material) {
        Shop shop = shops.get(shopId);
        Integer price = shop == null ? null : shop.prices.get(material);
        if (price == null) {
            player.sendMessage("That offer is no longer available.");
            player.closeInventory();
            return;
        }
        StockSlot stockSlot = findStock(shop, material);
        if (stockSlot == null) {
            player.sendMessage("That item is out of stock.");
            player.closeInventory();
            return;
        }
        ItemStack product = stockSlot.item.clone();
        product.setAmount(1);
        if (count(player.getInventory(), Material.EMERALD) < price) {
            player.sendMessage("You need " + price + " emerald(s) for that item.");
            return;
        }
        if (!canFit(player.getInventory(), product)) {
            player.sendMessage("Your inventory is full.");
            return;
        }
        if (!canStorePayment(shop, stockSlot, price)) {
            player.sendMessage("The shop's linked chests have no room for the emerald payment.");
            return;
        }
        stockSlot.item.setAmount(stockSlot.item.getAmount() - 1);
        if (stockSlot.item.getAmount() == 0) {
            stockSlot.inventory.setItem(stockSlot.slot, null);
        }
        remove(player.getInventory(), Material.EMERALD, price);
        storePayment(shop, price);
        player.getInventory().addItem(product);
        player.sendMessage("Bought 1 " + material.getKey() + " for " + price + " emerald(s).");
        updateUnavailableState(shopId, shop, true, material);
        openStore(player, shopId);
    }

    private StockSlot findStock(Shop shop, Material material) {
        for (Inventory inventory : linkedInventories(shop)) {
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item == null || item.getType() != material) {
                    continue;
                }
                return new StockSlot(inventory, slot, item);
            }
        }
        return null;
    }

    private boolean canStorePayment(Shop shop, StockSlot sold, int amount) {
        int capacity = sold.item.getAmount() == 1 ? 64 : 0;
        for (Inventory inventory : linkedInventories(shop)) {
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                if (inventory == sold.inventory && slot == sold.slot) {
                    continue;
                }
                ItemStack item = inventory.getItem(slot);
                if (item == null) {
                    capacity += 64;
                } else if (item.getType() == Material.EMERALD) {
                    capacity += item.getMaxStackSize() - item.getAmount();
                }
                if (capacity >= amount) {
                    return true;
                }
            }
        }
        return capacity >= amount;
    }

    private void storePayment(Shop shop, int amount) {
        ItemStack payment = new ItemStack(Material.EMERALD, amount);
        for (Inventory inventory : linkedInventories(shop)) {
            Map<Integer, ItemStack> remaining = inventory.addItem(payment);
            if (remaining.isEmpty()) {
                return;
            }
            payment = remaining.values().iterator().next();
        }
        throw new IllegalStateException("Validated shopkeeper payment did not fit in linked chests");
    }

    private Map<Material, Stock> stock(Shop shop) {
        Map<Material, Stock> result = new LinkedHashMap<>();
        for (Inventory inventory : linkedInventories(shop)) {
            for (ItemStack item : inventory.getContents()) {
                if (item == null || !shop.prices.containsKey(item.getType())) {
                    continue;
                }
                Stock current = result.computeIfAbsent(item.getType(), ignored -> new Stock(item.clone()));
                current.amount += item.getAmount();
            }
        }
        return result;
    }

    private List<Inventory> linkedInventories(Shop shop) {
        Set<Inventory> result = new LinkedHashSet<>();
        for (BlockLocation location : shop.chests) {
            World world = Bukkit.getWorld(location.world);
            if (world != null && world.isChunkLoaded(location.x >> 4, location.z >> 4)
                    && world.getBlockAt(location.x, location.y, location.z).getState() instanceof Chest chest) {
                result.add(chest.getInventory());
            }
        }
        return List.copyOf(result);
    }

    private List<Shop> linkedShops(Set<BlockLocation> locations) {
        return shops.values().stream()
                .filter(shop -> shop.chests.stream().anyMatch(locations::contains))
                .toList();
    }

    private boolean isProtectedChest(org.bukkit.block.Block block) {
        if (block.getState() instanceof Chest chest) {
            return !linkedShops(chestLocations(chest)).isEmpty();
        }
        return false;
    }

    private static Set<BlockLocation> chestLocations(Chest chest) {
        Set<BlockLocation> locations = new LinkedHashSet<>();
        InventoryHolder holder = chest.getInventory().getHolder(false);
        if (holder instanceof DoubleChest doubleChest) {
            addChestLocation(locations, doubleChest.getLeftSide(false));
            addChestLocation(locations, doubleChest.getRightSide(false));
        } else {
            locations.add(BlockLocation.from(chest.getLocation()));
        }
        return locations;
    }

    private static void addChestLocation(Set<BlockLocation> locations, InventoryHolder holder) {
        if (holder instanceof Chest chest) {
            locations.add(BlockLocation.from(chest.getLocation()));
        }
    }

    private static LivingEntity attacker(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof LivingEntity living) {
            return living;
        }
        if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof LivingEntity living) {
            return living;
        }
        return null;
    }

    private static void applyThornsDamage(LivingEntity attacker) {
        double remaining = THORNS_DAMAGE;
        double absorption = attacker.getAbsorptionAmount();
        if (absorption > 0.0) {
            double absorbed = Math.min(absorption, remaining);
            attacker.setAbsorptionAmount(absorption - absorbed);
            remaining -= absorbed;
        }
        if (remaining > 0.0) {
            attacker.setHealth(Math.max(0.0, attacker.getHealth() - remaining));
        }
        attacker.playHurtAnimation(0.0F);
        if (attacker instanceof Player player) {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_HURT,
                    SoundCategory.PLAYERS, 1.0F, 1.0F);
        }
    }

    private static void applyShopkeeperProtection(Villager villager, double maxHealth) {
        villager.setPersistent(true);
        villager.setRemoveWhenFarAway(false);
        setAttribute(villager, Attribute.MAX_HEALTH, maxHealth);
        setAttribute(villager, Attribute.ARMOR, SHOPKEEPER_ARMOR);
        setAttribute(villager, Attribute.ARMOR_TOUGHNESS, SHOPKEEPER_ARMOR_TOUGHNESS);
    }

    private static void applyShopkeeperName(Villager villager, Shop shop) {
        villager.customName(Component.text(shop.name));
        villager.setCustomNameVisible(true);
    }

    private static void applyShopkeeperMovement(Villager villager, Shop shop) {
        AttributeInstance movement = villager.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movement != null) {
            double multiplier = shop.boundaryRadius <= MIN_BOUNDARY_RADIUS
                    ? 0.0 : shop.movementSpeed;
            movement.setBaseValue(movement.getDefaultValue() * multiplier);
        }
    }

    private static void setAttribute(Villager villager, Attribute attribute, double value) {
        AttributeInstance instance = villager.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    private void enforceShopkeeperBoundaries() {
        for (Map.Entry<UUID, Shop> entry : shops.entrySet()) {
            ShopCenter center = entry.getValue().center;
            org.bukkit.entity.Entity entity = Bukkit.getEntity(entry.getKey());
            if (center == null || !(entity instanceof Villager villager) || !villager.isValid()) {
                continue;
            }
            Location location = villager.getLocation();
            World world = Bukkit.getWorld(center.world);
            if (world == null) {
                continue;
            }
            Location target = new Location(world, center.x, center.y, center.z,
                    location.getYaw(), location.getPitch());
            if (!center.world.equals(location.getWorld().getName())) {
                villager.teleport(target);
                boundaryReturnChecks.remove(entry.getKey());
                continue;
            }

            if (entry.getValue().boundaryRadius <= MIN_BOUNDARY_RADIUS) {
                villager.getPathfinder().stopPathfinding();
                if (location.distanceSquared(target) > 0.01) {
                    villager.teleport(target);
                }
                boundaryReturnChecks.remove(entry.getKey());
                continue;
            }

            double xDistance = Math.abs(location.getX() - center.x);
            double zDistance = Math.abs(location.getZ() - center.z);
            double returnThreshold = Math.max(0.5, entry.getValue().boundaryRadius - 0.5);
            if (xDistance <= returnThreshold && zDistance <= returnThreshold) {
                boundaryReturnChecks.remove(entry.getKey());
                continue;
            }

            int checks = boundaryReturnChecks.merge(entry.getKey(), 1, Integer::sum);
            double teleportThreshold = Math.max(entry.getValue().boundaryRadius * 3.0,
                    entry.getValue().boundaryRadius + 5.0);
            if (xDistance > teleportThreshold || zDistance > teleportThreshold
                    || checks >= SHOP_PATHFINDING_TIMEOUT_CHECKS) {
                villager.teleport(target);
                boundaryReturnChecks.remove(entry.getKey());
            } else {
                villager.getPathfinder().moveTo(target, 1.0);
            }
        }
    }

    private void checkUnavailableListingTransitions() {
        for (Map.Entry<UUID, Shop> entry : shops.entrySet()) {
            updateUnavailableState(entry.getKey(), entry.getValue(), false, null);
        }
    }

    private void updateUnavailableState(UUID shopId, Shop shop, boolean loadChunks,
                                        Material forcedListing) {
        if (!isLiveShopkeeper(shopId, shop, loadChunks)) {
            unavailableListingStates.remove(shopId);
            return;
        }
        Set<Material> unavailable = unavailableListings(shop, loadChunks);
        if (unavailable == null) {
            return;
        }
        Set<Material> previous = unavailableListingStates.put(shopId, Set.copyOf(unavailable));
        Set<Material> newlyUnavailable = new LinkedHashSet<>(unavailable);
        if (previous != null) {
            newlyUnavailable.removeAll(previous);
        } else if (forcedListing == null) {
            newlyUnavailable.clear();
        } else {
            newlyUnavailable.retainAll(Set.of(forcedListing));
        }
        Player owner = Bukkit.getPlayer(shop.owner);
        if (owner != null && owner.isOnline()) {
            newlyUnavailable.forEach(material -> notifyUnavailable(owner, shop, material));
        }
    }

    private boolean isLiveShopkeeper(UUID shopId, Shop shop, boolean loadChunk) {
        if (shop.center == null) {
            return false;
        }
        World world = Bukkit.getWorld(shop.center.world);
        if (world == null) {
            return false;
        }
        int chunkX = ((int) Math.floor(shop.center.x)) >> 4;
        int chunkZ = ((int) Math.floor(shop.center.z)) >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            if (!loadChunk) {
                return false;
            }
            world.getChunkAt(chunkX, chunkZ);
        }
        org.bukkit.entity.Entity entity = Bukkit.getEntity(shopId);
        return entity instanceof Villager villager
                && villager.isValid()
                && !villager.isDead()
                && shop.owner.equals(owner(villager));
    }

    private Set<Material> unavailableListings(Shop shop, boolean loadChunks) {
        if (shop.prices.isEmpty()) {
            return Set.of();
        }
        if (shop.chests.isEmpty()) {
            return Set.copyOf(shop.prices.keySet());
        }
        for (BlockLocation location : shop.chests) {
            World world = Bukkit.getWorld(location.world);
            if (world == null) {
                continue;
            }
            if (!world.isChunkLoaded(location.x >> 4, location.z >> 4)) {
                if (!loadChunks) {
                    return null;
                }
                world.getChunkAt(location.x >> 4, location.z >> 4);
            }
        }
        Map<Material, Stock> available = stock(shop);
        Set<Material> unavailable = new LinkedHashSet<>(shop.prices.keySet());
        unavailable.removeAll(available.keySet());
        return unavailable;
    }

    private static void notifyUnavailable(Player owner, Shop shop, Material material) {
        owner.sendMessage("Your shopkeeper " + shop.name + " is out of stock for "
                + material.getKey() + ".");
    }

    private Shop selectedOwnedShop(Player player) {
        UUID shopId = selectedShops.get(player.getUniqueId());
        Shop shop = shopId == null ? null : shops.get(shopId);
        return shop != null && shop.owner.equals(player.getUniqueId()) ? shop : null;
    }

    private UUID owner(Villager villager) {
        String value = villager.getPersistentDataContainer().get(shopkeeperOwnerKey, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isShopkeeperTool(ItemStack item) {
        if (item == null || !item.getType().name().endsWith("_SHOVEL") || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta.hasCustomName() && TOOL_NAME.equalsIgnoreCase(
                PlainTextComponentSerializer.plainText().serialize(meta.customName()));
    }

    private static int count(Inventory inventory, Material material) {
        int amount = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getType() == material) {
                amount += item.getAmount();
            }
        }
        return amount;
    }

    private static void remove(Inventory inventory, Material material, int amount) {
        for (int slot = 0; slot < inventory.getSize() && amount > 0; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != material) {
                continue;
            }
            int removed = Math.min(amount, item.getAmount());
            item.setAmount(item.getAmount() - removed);
            amount -= removed;
            if (item.getAmount() == 0) {
                inventory.setItem(slot, null);
            }
        }
    }

    private static boolean canFit(Inventory inventory, ItemStack item) {
        if (inventory.firstEmpty() >= 0) {
            return true;
        }
        for (ItemStack existing : inventory.getStorageContents()) {
            if (existing != null && existing.isSimilar(item)
                    && existing.getAmount() < existing.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    private void load() {
        YamlConfiguration data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = data.getConfigurationSection("shops");
        if (root == null) {
            return;
        }
        for (String idText : root.getKeys(false)) {
            try {
                UUID id = UUID.fromString(idText);
                ConfigurationSection section = root.getConfigurationSection(idText);
                UUID owner = UUID.fromString(section.getString("owner", ""));
                Shop shop = new Shop(owner, ShopCenter.read(section.getConfigurationSection("center")));
                shop.name = section.getString("name", "Shopkeeper");
                shop.boundaryRadius = bounded(section.getDouble(
                        "boundary-radius", DEFAULT_BOUNDARY_RADIUS),
                        MIN_BOUNDARY_RADIUS, MAX_BOUNDARY_RADIUS, DEFAULT_BOUNDARY_RADIUS);
                shop.movementSpeed = bounded(section.getDouble(
                        "return-speed", DEFAULT_MOVEMENT_SPEED),
                        MIN_MOVEMENT_SPEED, MAX_MOVEMENT_SPEED, DEFAULT_MOVEMENT_SPEED);
                shop.maxHealth = bounded(section.getDouble("max-health", DEFAULT_MAX_HEALTH),
                        MIN_MAX_HEALTH, MAX_MAX_HEALTH, DEFAULT_MAX_HEALTH);
                for (String encoded : section.getStringList("chests")) {
                    BlockLocation location = BlockLocation.parse(encoded);
                    if (location != null) {
                        shop.chests.add(location);
                    }
                }
                ConfigurationSection prices = section.getConfigurationSection("prices");
                if (prices != null) {
                    for (String materialName : prices.getKeys(false)) {
                        Material material = Material.matchMaterial(materialName);
                        int price = prices.getInt(materialName);
                        if (material != null && material.isItem() && price > 0) {
                            shop.prices.put(material, price);
                        }
                    }
                }
                shops.put(id, shop);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Ignoring invalid shopkeeper data entry: " + idText);
            }
        }
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        for (Map.Entry<UUID, Shop> entry : shops.entrySet()) {
            String path = "shops." + entry.getKey();
            Shop shop = entry.getValue();
            data.set(path + ".owner", shop.owner.toString());
            data.set(path + ".name", shop.name);
            if (shop.center != null) {
                data.set(path + ".center.world", shop.center.world);
                data.set(path + ".center.x", shop.center.x);
                data.set(path + ".center.y", shop.center.y);
                data.set(path + ".center.z", shop.center.z);
            }
            data.set(path + ".boundary-radius", shop.boundaryRadius);
            data.set(path + ".return-speed", shop.movementSpeed);
            data.set(path + ".max-health", shop.maxHealth);
            data.set(path + ".chests", shop.chests.stream().map(BlockLocation::encode).toList());
            for (Map.Entry<Material, Integer> price : shop.prices.entrySet()) {
                data.set(path + ".prices." + price.getKey().getKey().getKey(), price.getValue());
            }
        }
        try {
            data.save(dataFile);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save shopkeepers.yml: " + exception.getMessage());
        }
    }

    private static final class Shop {
        private final UUID owner;
        private ShopCenter center;
        private String name = "Shopkeeper";
        private double boundaryRadius = DEFAULT_BOUNDARY_RADIUS;
        private double movementSpeed = DEFAULT_MOVEMENT_SPEED;
        private double maxHealth = DEFAULT_MAX_HEALTH;
        private final Set<BlockLocation> chests = new LinkedHashSet<>();
        private final Map<Material, Integer> prices = new LinkedHashMap<>();

        private Shop(UUID owner, ShopCenter center) {
            this.owner = owner;
            this.center = center;
        }
    }

    private static double bounded(double value, double minimum, double maximum, double fallback) {
        return Double.isFinite(value) && value >= minimum && value <= maximum ? value : fallback;
    }


    private record ShopCenter(String world, double x, double y, double z) {
        private static ShopCenter from(Location location) {
            return new ShopCenter(location.getWorld().getName(),
                    location.getX(), location.getY(), location.getZ());
        }

        private static ShopCenter read(ConfigurationSection section) {
            if (section == null || !section.isString("world")) {
                return null;
            }
            return new ShopCenter(section.getString("world"), section.getDouble("x"),
                    section.getDouble("y"), section.getDouble("z"));
        }
    }

    private static final class Stock {
        private final ItemStack example;
        private int amount;

        private Stock(ItemStack example) {
            this.example = example;
        }
    }

    private record StockSlot(Inventory inventory, int slot, ItemStack item) {
    }

    private static final class StoreHolder implements InventoryHolder {
        private final UUID shopId;
        private Inventory inventory;

        private StoreHolder(UUID shopId) {
            this.shopId = shopId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record BlockLocation(String world, int x, int y, int z) {
        private static BlockLocation from(Location location) {
            return new BlockLocation(location.getWorld().getName(),
                    location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        private String encode() {
            return world + ";" + x + ";" + y + ";" + z;
        }

        private static BlockLocation parse(String value) {
            String[] parts = value.split(";", 4);
            if (parts.length != 4) {
                return null;
            }
            try {
                return new BlockLocation(parts[0], Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
