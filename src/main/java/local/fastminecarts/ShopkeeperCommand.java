package local.fastminecarts;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class ShopkeeperCommand implements BasicCommand {
    private static final String USAGE = "Usage: /shopkeeper <price <item> <emeralds>|unprice <item>|info>";

    private final ShopkeeperController controller;

    ShopkeeperCommand(ShopkeeperController controller) {
        this.controller = controller;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("This command can only be used by a player.");
            return;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("info")) {
            controller.describe(player);
            return;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("price")) {
            Material material = item(args[1]);
            int price;
            try {
                price = Integer.parseInt(args[2]);
            } catch (NumberFormatException ignored) {
                player.sendMessage(USAGE);
                return;
            }
            if (material == null) {
                player.sendMessage("Unknown item: " + args[1]);
            } else if (price < 1 || price > 64) {
                player.sendMessage("The price must be between 1 and 64 emeralds.");
            } else {
                controller.setPrice(player, material, price);
            }
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("unprice")) {
            Material material = item(args[1]);
            if (material == null) {
                player.sendMessage("Unknown item: " + args[1]);
            } else {
                controller.removePrice(player, material);
            }
            return;
        }
        player.sendMessage(USAGE);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("price", "unprice", "info").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("price")
                || args[0].equalsIgnoreCase("unprice"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of(Material.values()).stream()
                    .filter(Material::isItem)
                    .map(material -> material.getKey().toString())
                    .filter(value -> value.startsWith(prefix)
                            || value.substring("minecraft:".length()).startsWith(prefix))
                    .toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("price")) {
            return List.of("1", "2", "4", "8", "16", "32", "64").stream()
                    .filter(value -> value.startsWith(args[2])).toList();
        }
        return List.of();
    }

    private static Material item(String value) {
        Material material = Material.matchMaterial(value);
        return material != null && material.isItem() && !material.isAir() ? material : null;
    }
}
