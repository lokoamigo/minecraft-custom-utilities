package local.fastminecarts;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

final class ShulkerFindCommand implements BasicCommand {
    private static final String USAGE =
            "Usage: /shulkerfind [locate] <item> [quantity] [<item> [quantity] ...]";
    private static final long CONFIRMATION_TTL_MILLIS = Duration.ofSeconds(30).toMillis();

    private final Map<UUID, PendingTransfer> pendingTransfers = new HashMap<>();

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("This command can only be used by a player.");
            return;
        }
        if (args.length == 0) {
            player.sendMessage(USAGE);
            return;
        }

        boolean locateOnly = args[0].equalsIgnoreCase("locate");
        String[] requestArgs = locateOnly ? Arrays.copyOfRange(args, 1, args.length) : args;
        if (requestArgs.length == 0) {
            player.sendMessage(USAGE);
            return;
        }

        Map<Material, Integer> requested = parseRequests(player, requestArgs);
        if (requested == null) {
            return;
        }

        if (locateOnly) {
            search(player, requested);
            return;
        }

        PendingTransfer pending = pendingTransfers.get(player.getUniqueId());
        if (pending != null && pending.expiresAtMillis() >= System.currentTimeMillis()
                && pending.requested().equals(requested)) {
            pendingTransfers.remove(player.getUniqueId());
            completeTransfer(player, pending);
            return;
        }

        pendingTransfers.remove(player.getUniqueId());
        SearchResult result = search(player, requested);
        if (!result.hasAny()) {
            return;
        }

        TransferPlan plan = createTransferPlan(player, requested);
        if (plan.status() == PlanStatus.NO_SPACE) {
            player.sendMessage("Your inventory does not have enough space. Nothing was moved.");
            return;
        }
        if (plan.status() != PlanStatus.READY) {
            player.sendMessage("The requested items are no longer available. Nothing was moved.");
            return;
        }

        pendingTransfers.put(player.getUniqueId(), new PendingTransfer(
                Collections.unmodifiableMap(new LinkedHashMap<>(requested)),
                System.currentTimeMillis() + CONFIRMATION_TTL_MILLIS));
        player.sendMessage("Move the available requested items into your inventory? "
                + "Run the same command again within 30 seconds to confirm "
                + "(press Up Arrow, then Enter).");
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 0) {
            return List.of("locate");
        }
        boolean locateOnly = args[0].equalsIgnoreCase("locate");
        int requestStart = locateOnly ? 1 : 0;
        SuggestionType type = suggestionType(
                Arrays.copyOfRange(args, requestStart, args.length - 1));
        if (type == SuggestionType.ITEM) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>(suggestibleItems(source).stream()
                    .filter(name -> matchesItemName(name, prefix))
                    .toList());
            if (args.length == 1) {
                if ("locate".startsWith(prefix)) {
                    suggestions.addFirst("locate");
                }
            }
            return suggestions;
        }
        String prefix = args[args.length - 1];
        Collection<String> quantities = quantitySuggestions(source, args);
        return quantities.stream()
                .filter(value -> value.startsWith(prefix)).toList();
    }

    private static Collection<String> quantitySuggestions(
            CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player) || args.length < 2) {
            return List.of("1", "16", "32", "64");
        }

        Material material = Material.matchMaterial(args[args.length - 2]);
        if (material == null || !material.isItem()) {
            return List.of();
        }

        int available = countInShulkerBoxes(player, material);
        if (available == 0) {
            return List.of();
        }

        TreeSet<Integer> quantities = new TreeSet<>();
        for (int standard : List.of(1, 16, 32, 64)) {
            if (standard <= available) {
                quantities.add(standard);
            }
        }
        quantities.add(available);
        return quantities.stream().map(String::valueOf).toList();
    }

    private static Collection<String> suggestibleItems(CommandSourceStack source) {
        if (!(source.getSender() instanceof Player player)) {
            return Arrays.stream(Material.values())
                    .filter(Material::isItem)
                    .map(material -> material.getKey().toString())
                    .toList();
        }

        TreeSet<String> available = new TreeSet<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            ShulkerBox shulker = getShulker(item);
            if (shulker == null) {
                continue;
            }
            for (ItemStack content : shulker.getInventory().getContents()) {
                if (content != null && !content.getType().isAir()) {
                    available.add(content.getType().getKey().toString());
                }
            }
        }
        return available;
    }

    private static int countInShulkerBoxes(Player player, Material material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            ShulkerBox shulker = getShulker(item);
            if (shulker == null) {
                continue;
            }
            for (ItemStack content : shulker.getInventory().getContents()) {
                if (content != null && content.getType() == material) {
                    total += content.getAmount();
                }
            }
        }
        return total;
    }

    private static SuggestionType suggestionType(String[] completedArgs) {
        SuggestionType type = SuggestionType.ITEM;
        for (String arg : completedArgs) {
            if (type == SuggestionType.QUANTITY) {
                if (!isPositiveInteger(arg)) {
                    return SuggestionType.QUANTITY;
                }
                type = SuggestionType.ITEM;
                continue;
            }

            Material material = Material.matchMaterial(arg);
            if (material == null || !material.isItem()) {
                return SuggestionType.ITEM;
            }
            type = material.getMaxStackSize() == 1
                    ? SuggestionType.ITEM : SuggestionType.QUANTITY;
        }
        return type;
    }

    private static boolean matchesItemName(String name, String input) {
        if (name.startsWith(input)) {
            return true;
        }
        int namespaceSeparator = name.indexOf(':');
        String path = namespaceSeparator < 0 ? name : name.substring(namespaceSeparator + 1);
        return path.contains(input);
    }

    private static void completeTransfer(Player player, PendingTransfer pending) {
        TransferPlan plan = createTransferPlan(player, pending.requested());
        if (plan.status() == PlanStatus.MISSING_ITEMS) {
            player.sendMessage("None of the requested items are available anymore. Nothing was moved.");
            return;
        }
        if (plan.status() == PlanStatus.NO_SPACE) {
            player.sendMessage("Your inventory no longer has enough space. Nothing was moved.");
            return;
        }

        if (!contentsMatch(player.getInventory().getStorageContents(), plan.originalContents())) {
            player.sendMessage("Your inventory changed during the transfer check. Nothing was moved; run the command again.");
            return;
        }
        player.getInventory().setStorageContents(plan.finalContents());
        player.sendMessage("Moved " + formatAmounts(plan.moved())
                + " from your shulker boxes into your inventory.");
    }

    private static SearchResult search(Player player, Map<Material, Integer> requested) {
        Map<Material, Integer> totals = new LinkedHashMap<>();
        requested.keySet().forEach(material -> totals.put(material, 0));
        int boxesSearched = 0;
        int matchingBoxes = 0;

        ItemStack[] inventory = player.getInventory().getStorageContents();
        for (int slot = 0; slot < inventory.length; slot++) {
            ItemStack item = inventory[slot];
            ShulkerBox shulker = getShulker(item);
            if (shulker == null) {
                continue;
            }
            boxesSearched++;

            Map<Material, Integer> inBox = countRequested(
                    shulker.getInventory().getContents(), requested.keySet());
            if (inBox.isEmpty()) {
                continue;
            }

            matchingBoxes++;
            inBox.forEach((material, amount) -> totals.merge(material, amount, Integer::sum));
            player.sendMessage(String.format(Locale.ROOT, "%s (inventory %s): %s",
                    boxName(item), slotName(slot), formatAmounts(inBox)));
        }

        if (boxesSearched == 0) {
            player.sendMessage("No shulker boxes found in your inventory.");
        } else if (matchingBoxes == 0) {
            player.sendMessage("Searched " + boxesSearched + " shulker box"
                    + (boxesSearched == 1 ? "" : "es")
                    + "; none contained the requested items.");
        }

        List<String> summary = new ArrayList<>();
        requested.forEach((material, quantity) -> summary.add(String.format(Locale.ROOT,
                "%s %d/%d%s", displayName(material), totals.get(material), quantity,
                totals.get(material) >= quantity
                        ? " (enough)"
                        : " (missing " + (quantity - totals.get(material)) + ")")));
        player.sendMessage("Totals: " + String.join(", ", summary));
        return new SearchResult(totals, requested);
    }

    private static TransferPlan createTransferPlan(
            Player player, Map<Material, Integer> requested) {
        ItemStack[] originalContents = cloneContents(player.getInventory().getStorageContents());
        ItemStack[] finalContents = cloneContents(originalContents);
        Map<Material, Integer> remaining = new LinkedHashMap<>(requested);
        Map<Material, Integer> movedAmounts = new LinkedHashMap<>();
        List<ItemStack> extracted = new ArrayList<>();

        for (int slot = 0; slot < finalContents.length; slot++) {
            ItemStack boxItem = finalContents[slot];
            ShulkerBox shulker = getShulker(boxItem);
            if (shulker == null) {
                continue;
            }

            ItemStack[] boxContents = cloneContents(shulker.getInventory().getContents());
            boolean changed = false;
            for (int boxSlot = 0; boxSlot < boxContents.length; boxSlot++) {
                ItemStack content = boxContents[boxSlot];
                int needed = content == null ? 0 : remaining.getOrDefault(content.getType(), 0);
                if (needed == 0) {
                    continue;
                }

                int amount = Math.min(needed, content.getAmount());
                ItemStack movedItem = content.clone();
                movedItem.setAmount(amount);
                extracted.add(movedItem);
                movedAmounts.merge(content.getType(), amount, Integer::sum);
                remaining.put(content.getType(), needed - amount);

                if (amount == content.getAmount()) {
                    boxContents[boxSlot] = null;
                } else {
                    content.setAmount(content.getAmount() - amount);
                }
                changed = true;
            }

            if (changed) {
                shulker.getInventory().setContents(boxContents);
                BlockStateMeta meta = (BlockStateMeta) boxItem.getItemMeta();
                meta.setBlockState(shulker);
                boxItem.setItemMeta(meta);
            }
        }

        if (extracted.isEmpty()) {
            return new TransferPlan(PlanStatus.MISSING_ITEMS, null, null, Map.of());
        }
        for (ItemStack item : extracted) {
            if (!addToStorage(finalContents, item)) {
                return new TransferPlan(PlanStatus.NO_SPACE, null, null, Map.of());
            }
        }
        return new TransferPlan(PlanStatus.READY, originalContents, finalContents,
                Collections.unmodifiableMap(movedAmounts));
    }

    private static boolean contentsMatch(ItemStack[] current, ItemStack[] expected) {
        if (current.length != expected.length) {
            return false;
        }
        for (int slot = 0; slot < current.length; slot++) {
            ItemStack currentItem = current[slot];
            ItemStack expectedItem = expected[slot];
            if (currentItem == null || currentItem.isEmpty()) {
                if (expectedItem != null && !expectedItem.isEmpty()) {
                    return false;
                }
            } else if (expectedItem == null || expectedItem.isEmpty()
                    || currentItem.getAmount() != expectedItem.getAmount()
                    || !currentItem.isSimilar(expectedItem)) {
                return false;
            }
        }
        return true;
    }

    private static boolean addToStorage(ItemStack[] storage, ItemStack item) {
        int remaining = item.getAmount();
        for (ItemStack existing : storage) {
            if (existing != null && existing.isSimilar(item)
                    && existing.getAmount() < existing.getMaxStackSize()) {
                int added = Math.min(remaining, existing.getMaxStackSize() - existing.getAmount());
                existing.setAmount(existing.getAmount() + added);
                remaining -= added;
                if (remaining == 0) {
                    return true;
                }
            }
        }
        for (int slot = 0; slot < storage.length; slot++) {
            if (storage[slot] != null) {
                continue;
            }
            int added = Math.min(remaining, item.getMaxStackSize());
            ItemStack stack = item.clone();
            stack.setAmount(added);
            storage[slot] = stack;
            remaining -= added;
            if (remaining == 0) {
                return true;
            }
        }
        return false;
    }

    private static Map<Material, Integer> parseRequests(Player player, String[] args) {
        Map<Material, Integer> requested = new LinkedHashMap<>();
        for (int index = 0; index < args.length;) {
            Material material = Material.matchMaterial(args[index]);
            if (material == null || !material.isItem()) {
                player.sendMessage("Unknown item: " + args[index]);
                return null;
            }
            index++;

            int quantity = 1;
            if (material.getMaxStackSize() > 1) {
                if (index >= args.length) {
                    player.sendMessage("A quantity is required for " + displayName(material) + ".");
                    return null;
                }
                Integer parsed = parsePositiveInteger(args[index]);
                if (parsed == null) {
                    player.sendMessage("Quantity for " + displayName(material)
                            + " must be a whole number above 0.");
                    return null;
                }
                quantity = parsed;
                index++;
            } else if (index < args.length && isPositiveInteger(args[index])) {
                quantity = Integer.parseInt(args[index]);
                index++;
            }

            if (quantity <= 0) {
                player.sendMessage("Quantity for " + displayName(material)
                        + " must be above 0.");
                return null;
            }
            try {
                requested.merge(material, quantity, Math::addExact);
            } catch (ArithmeticException exception) {
                player.sendMessage("Requested quantity for " + displayName(material)
                        + " is too large.");
                return null;
            }
        }
        return requested;
    }

    private static boolean isPositiveInteger(String value) {
        return parsePositiveInteger(value) != null;
    }

    private static Integer parsePositiveInteger(String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static ShulkerBox getShulker(ItemStack item) {
        if (item == null || !item.getType().name().endsWith("SHULKER_BOX")
                || !(item.getItemMeta() instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox shulker)) {
            return null;
        }
        return shulker;
    }

    private static Map<Material, Integer> countRequested(
            ItemStack[] contents, Collection<Material> requested) {
        Map<Material, Integer> counts = new LinkedHashMap<>();
        for (ItemStack content : contents) {
            if (content != null && requested.contains(content.getType())) {
                counts.merge(content.getType(), content.getAmount(), Integer::sum);
            }
        }
        return counts;
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = new ItemStack[contents.length];
        for (int slot = 0; slot < contents.length; slot++) {
            copy[slot] = contents[slot] == null ? null : contents[slot].clone();
        }
        return copy;
    }

    private static String boxName(ItemStack item) {
        if (item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        return displayName(item.getType());
    }

    private static String slotName(int slot) {
        return slot < 9 ? "hotbar " + (slot + 1) : "slot " + (slot + 1);
    }

    private static String formatAmounts(Map<Material, Integer> amounts) {
        return amounts.entrySet().stream()
                .map(entry -> displayName(entry.getKey()) + " x" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private static String displayName(Material material) {
        String[] words = material.getKey().getKey().split("_");
        for (int index = 0; index < words.length; index++) {
            words[index] = Character.toUpperCase(words[index].charAt(0))
                    + words[index].substring(1);
        }
        return String.join(" ", words);
    }

    private record PendingTransfer(Map<Material, Integer> requested, long expiresAtMillis) {
    }

    private record SearchResult(Map<Material, Integer> totals, Map<Material, Integer> requested) {
        private boolean hasAny() {
            return totals.values().stream().anyMatch(amount -> amount > 0);
        }
    }

    private record TransferPlan(PlanStatus status, ItemStack[] originalContents,
                                ItemStack[] finalContents, Map<Material, Integer> moved) {
    }

    private enum PlanStatus {
        READY,
        MISSING_ITEMS,
        NO_SPACE
    }

    private enum SuggestionType {
        ITEM,
        QUANTITY
    }
}
