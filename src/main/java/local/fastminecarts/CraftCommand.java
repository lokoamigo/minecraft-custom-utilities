package local.fastminecarts;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.CraftingRecipe;
import org.bukkit.inventory.ItemCraftResult;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

final class CraftCommand implements BasicCommand {
    private static final String USAGE = "Usage: /craft <item> [count]";

    private final CraftCommandWorkbench workbench;

    CraftCommand(CraftCommandWorkbench workbench) {
        this.workbench = workbench;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("This command can only be used by a player.");
            return;
        }
        if (args.length < 1 || args.length > 2) {
            player.sendMessage(USAGE);
            return;
        }

        Material requested = Material.matchMaterial(args[0]);
        if (requested == null || !requested.isItem() || requested.isAir()) {
            player.sendMessage("Unknown craftable item: " + args[0]);
            return;
        }

        int requestedOperations = 1;
        if (args.length == 2) {
            try {
                requestedOperations = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                player.sendMessage(USAGE);
                return;
            }
            if (requestedOperations < 1 || requestedOperations > CraftCommandWorkbench.MAX_USES) {
                player.sendMessage("Count must be between 1 and " + CraftCommandWorkbench.MAX_USES + ".");
                return;
            }
        }

        CraftCommandWorkbench.Bench bench = workbench.findOrInitialize(player);
        if (bench == null) {
            return;
        }

        List<CraftingRecipe> recipes = supportedRecipes(player, requested);
        if (recipes.isEmpty()) {
            player.sendMessage("You have not unlocked a supported shaped or shapeless recipe for "
                    + requested.getKey() + ".");
            return;
        }

        craft(player, requested, requestedOperations, bench, recipes);
    }

    private void craft(Player player, Material requested, int requestedOperations,
                       CraftCommandWorkbench.Bench bench, List<CraftingRecipe> recipes) {
        ItemStack[] simulated = cloneContents(player.getInventory().getStorageContents());
        int remainingUses = bench.remainingUses();
        int completed = 0;
        int outputItems = 0;
        StopReason stopReason = StopReason.REQUEST_COMPLETE;

        while (completed < requestedOperations) {
            if (remainingUses <= 0) {
                stopReason = StopReason.BENCH_BROKE;
                break;
            }

            PlannedCraft plan = firstCraftable(player, requested, recipes, simulated, bench.slot());
            if (plan == null) {
                stopReason = StopReason.MISSING_INGREDIENTS;
                break;
            }

            ItemStack[] next = cloneContents(simulated);
            consumeIngredients(next, plan.sourceSlots());

            remainingUses--;
            if (remainingUses == 0) {
                next[bench.slot()] = null;
            } else {
                ItemStack updatedBench = next[bench.slot()];
                workbench.applyState(updatedBench, remainingUses);
            }

            List<ItemStack> additions = new ArrayList<>();
            additions.addAll(nonAirItems(plan.result().getResultingMatrix()));
            additions.addAll(nonAirItems(plan.result().getOverflowItems()));
            additions.add(plan.result().getResult().clone());
            if (!addAll(next, additions)) {
                stopReason = StopReason.NO_SPACE;
                break;
            }

            simulated = next;
            completed++;
            outputItems += plan.result().getResult().getAmount();
        }

        if (completed == 0) {
            player.sendMessage(switch (stopReason) {
                case NO_SPACE -> "Your inventory has no room for the crafted item or returned containers.";
                case MISSING_INGREDIENTS -> "You do not have the ingredients for a supported recipe for "
                        + requested.getKey() + ".";
                case BENCH_BROKE -> "That command workbench has no uses remaining.";
                case REQUEST_COMPLETE -> "Nothing was crafted.";
            });
            return;
        }

        player.getInventory().setStorageContents(simulated);
        String message = "Crafted " + outputItems + " × " + requested.getKey()
                + " in " + completed + " recipe operation" + (completed == 1 ? "" : "s") + ".";
        if (remainingUses == 0) {
            message += " The command workbench broke.";
        } else {
            message += " Workbench uses remaining: " + remainingUses + "/"
                    + CraftCommandWorkbench.MAX_USES + ".";
        }
        if (completed < requestedOperations && stopReason != StopReason.BENCH_BROKE) {
            message += switch (stopReason) {
                case NO_SPACE -> " Stopped because the remaining results would not fit.";
                case MISSING_INGREDIENTS -> " Stopped because no more matching ingredients were available.";
                default -> "";
            };
        }
        player.sendMessage(message);
    }

    private PlannedCraft firstCraftable(Player player, Material requested,
                                        List<CraftingRecipe> recipes,
                                        ItemStack[] contents, int benchSlot) {
        for (CraftingRecipe recipe : recipes) {
            List<Ingredient> ingredients = ingredients(recipe);
            int[] sourceSlots = allocateIngredients(ingredients, contents, benchSlot);
            if (sourceSlots == null) {
                continue;
            }

            ItemStack[] matrix = new ItemStack[9];
            for (int i = 0; i < ingredients.size(); i++) {
                ItemStack ingredient = contents[sourceSlots[i]].clone();
                ingredient.setAmount(1);
                matrix[ingredients.get(i).matrixSlot()] = ingredient;
            }

            ItemCraftResult result = Bukkit.getServer().craftItemResult(matrix, player.getWorld(), player);
            if (!result.getResult().getType().isAir() && result.getResult().getType() == requested) {
                return new PlannedCraft(sourceSlots, result);
            }
        }
        return null;
    }

    private static List<CraftingRecipe> supportedRecipes(Player player, Material requested) {
        List<CraftingRecipe> result = new ArrayList<>();
        for (Recipe recipe : Bukkit.getRecipesFor(new ItemStack(requested))) {
            if (recipe instanceof CraftingRecipe craftingRecipe
                    && (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)
                    && craftingRecipe.getResult().getType() == requested
                    && player.hasDiscoveredRecipe(craftingRecipe.getKey())) {
                result.add(craftingRecipe);
            }
        }
        return result;
    }

    private static List<Ingredient> ingredients(CraftingRecipe recipe) {
        List<Ingredient> result = new ArrayList<>();
        if (recipe instanceof ShapedRecipe shaped) {
            String[] shape = shaped.getShape();
            var choices = shaped.getChoiceMap();
            for (int row = 0; row < shape.length; row++) {
                for (int column = 0; column < shape[row].length(); column++) {
                    char symbol = shape[row].charAt(column);
                    RecipeChoice choice = choices.get(symbol);
                    if (symbol != ' ' && choice != null) {
                        result.add(new Ingredient(row * 3 + column, choice));
                    }
                }
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            List<RecipeChoice> choices = shapeless.getChoiceList();
            for (int i = 0; i < choices.size(); i++) {
                result.add(new Ingredient(i, choices.get(i)));
            }
        }
        return result;
    }

    private int[] allocateIngredients(List<Ingredient> ingredients,
                                      ItemStack[] contents, int benchSlot) {
        int[] sourceSlots = new int[ingredients.size()];
        Arrays.fill(sourceSlots, -1);
        int[] used = new int[contents.length];
        return allocateIngredient(0, ingredients, contents, benchSlot, sourceSlots, used)
                ? sourceSlots : null;
    }

    private boolean allocateIngredient(int index, List<Ingredient> ingredients,
                                       ItemStack[] contents, int benchSlot,
                                       int[] sourceSlots, int[] used) {
        if (index == ingredients.size()) {
            return true;
        }
        RecipeChoice choice = ingredients.get(index).choice();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (slot == benchSlot || item == null || item.getType().isAir()
                    || workbench.isProtectedWorkbench(item)
                    || used[slot] >= item.getAmount() || !choice.test(item)) {
                continue;
            }
            used[slot]++;
            sourceSlots[index] = slot;
            if (allocateIngredient(index + 1, ingredients, contents, benchSlot, sourceSlots, used)) {
                return true;
            }
            used[slot]--;
            sourceSlots[index] = -1;
        }
        return false;
    }

    private static void consumeIngredients(ItemStack[] contents, int[] sourceSlots) {
        int[] amounts = new int[contents.length];
        for (int slot : sourceSlots) {
            amounts[slot]++;
        }
        for (int slot = 0; slot < amounts.length; slot++) {
            if (amounts[slot] == 0) {
                continue;
            }
            ItemStack item = contents[slot];
            item.setAmount(item.getAmount() - amounts[slot]);
            if (item.getAmount() == 0) {
                contents[slot] = null;
            }
        }
    }

    private static boolean addAll(ItemStack[] contents, Collection<ItemStack> additions) {
        for (ItemStack original : additions) {
            if (original == null || original.getType().isAir() || original.getAmount() == 0) {
                continue;
            }
            ItemStack addition = original.clone();
            for (ItemStack existing : contents) {
                if (existing == null || !existing.isSimilar(addition)) {
                    continue;
                }
                int space = existing.getMaxStackSize() - existing.getAmount();
                int moved = Math.min(space, addition.getAmount());
                existing.setAmount(existing.getAmount() + moved);
                addition.setAmount(addition.getAmount() - moved);
                if (addition.getAmount() == 0) {
                    break;
                }
            }
            while (addition.getAmount() > 0) {
                int empty = firstEmpty(contents);
                if (empty < 0) {
                    return false;
                }
                int moved = Math.min(addition.getMaxStackSize(), addition.getAmount());
                ItemStack placed = addition.clone();
                placed.setAmount(moved);
                contents[empty] = placed;
                addition.setAmount(addition.getAmount() - moved);
            }
        }
        return true;
    }

    private static int firstEmpty(ItemStack[] contents) {
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] == null || contents[i].getType().isAir()) {
                return i;
            }
        }
        return -1;
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] result = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            result[i] = contents[i] == null ? null : contents[i].clone();
        }
        return result;
    }

    private static List<ItemStack> nonAirItems(ItemStack[] items) {
        return Arrays.stream(items)
                .filter(item -> item != null && !item.getType().isAir() && item.getAmount() > 0)
                .map(ItemStack::clone)
                .toList();
    }

    private static List<ItemStack> nonAirItems(Collection<ItemStack> items) {
        return items.stream()
                .filter(item -> item != null && !item.getType().isAir() && item.getAmount() > 0)
                .map(ItemStack::clone)
                .toList();
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            return List.of();
        }
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            TreeSet<String> outputs = new TreeSet<>();
            Iterator<Recipe> recipes = Bukkit.recipeIterator();
            while (recipes.hasNext()) {
                Recipe recipe = recipes.next();
                if (recipe instanceof CraftingRecipe craftingRecipe
                        && (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe)
                        && player.hasDiscoveredRecipe(craftingRecipe.getKey())) {
                    String name = craftingRecipe.getResult().getType().getKey().toString();
                    String shortName = craftingRecipe.getResult().getType().getKey().getKey();
                    if (name.startsWith(prefix) || shortName.startsWith(prefix)) {
                        outputs.add(name);
                    }
                }
            }
            return outputs;
        }
        if (args.length == 2) {
            String prefix = args[1];
            return List.of("1", "8", "16", "32", "64").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private enum StopReason {
        REQUEST_COMPLETE,
        MISSING_INGREDIENTS,
        NO_SPACE,
        BENCH_BROKE
    }

    private record Ingredient(int matrixSlot, RecipeChoice choice) {
    }

    private record PlannedCraft(int[] sourceSlots, ItemCraftResult result) {
    }
}
