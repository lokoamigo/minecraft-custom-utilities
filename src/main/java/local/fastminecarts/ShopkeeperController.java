package local.fastminecarts;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
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

    private final MinecraftCustomUtilitiesPlugin plugin;
    private final NamespacedKey shopkeeperOwnerKey;
    private final NamespacedKey offerMaterialKey;
    private final File dataFile;
    private final Map<UUID, Shop> shops = new HashMap<>();
    private final Map<UUID, UUID> selectedShops = new HashMap<>();

    ShopkeeperController(MinecraftCustomUtilitiesPlugin plugin) {
        this.plugin = plugin;
        shopkeeperOwnerKey = new NamespacedKey(plugin, "shopkeeper-owner");
        offerMaterialKey = new NamespacedKey(plugin, "shopkeeper-offer-material");
        dataFile = new File(plugin.getDataFolder(), "shopkeepers.yml");
        load();
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
                shops.put(villager.getUniqueId(), new Shop(owner));
                save();
                player.sendMessage("Villager transformed into your shopkeeper.");
            } else {
                if (!shops.containsKey(villager.getUniqueId())) {
                    shops.put(villager.getUniqueId(), new Shop(owner));
                    save();
                }
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
                || !event.getAction().isLeftClick()
                || !isShopkeeperTool(event.getItem())
                || event.getClickedBlock() == null
                || !(event.getClickedBlock().getState() instanceof Chest)) {
            return;
        }

        Player player = event.getPlayer();
        Shop shop = selectedOwnedShop(player);
        if (shop == null) {
            player.sendMessage("First right-click your shopkeeper with the shopkeeper shovel.");
            event.setCancelled(true);
            return;
        }

        event.setCancelled(true);
        BlockLocation location = BlockLocation.from(event.getClickedBlock().getLocation());
        if (shop.chests.remove(location)) {
            player.sendMessage("Chest unlinked from the selected shopkeeper.");
        } else {
            shop.chests.add(location);
            player.sendMessage("Chest linked to the selected shopkeeper.");
        }
        save();
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

    void describe(Player player) {
        Shop shop = selectedOwnedShop(player);
        if (shop == null) {
            player.sendMessage("Select your shopkeeper by right-clicking it with the shopkeeper shovel.");
            return;
        }
        player.sendMessage("Selected shopkeeper: " + shop.chests.size() + " linked chest block(s), "
                + shop.prices.size() + " configured price(s).");
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
        Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("Shopkeeper"));
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
                Shop shop = new Shop(owner);
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
        private final Set<BlockLocation> chests = new LinkedHashSet<>();
        private final Map<Material, Integer> prices = new LinkedHashMap<>();

        private Shop(UUID owner) {
            this.owner = owner;
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
