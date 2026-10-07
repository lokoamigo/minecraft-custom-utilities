package local.fastminecarts;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class ShopkeeperCommand implements BasicCommand {
    private static final String USAGE = "Usage: /shopkeeper <price <item> <emeralds>|unprice <item>|radius <blocks>|speed <multiplier>|health <points>|name <name...>|info>";

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
        if (args.length == 2 && args[0].equalsIgnoreCase("radius")) {
            Double radius = number(args[1]);
            if (radius == null || radius < ShopkeeperController.MIN_BOUNDARY_RADIUS
                    || radius > ShopkeeperController.MAX_BOUNDARY_RADIUS) {
                player.sendMessage("Radius must be between 1 and 32 blocks.");
            } else {
                controller.setBoundaryRadius(player, radius);
            }
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("speed")) {
            Double speed = number(args[1]);
            if (speed == null || speed < ShopkeeperController.MIN_MOVEMENT_SPEED
                    || speed > ShopkeeperController.MAX_MOVEMENT_SPEED) {
                player.sendMessage("Speed must be between 0.1 and 2.0.");
            } else {
                controller.setMovementSpeed(player, speed);
            }
            return;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("health")) {
            Double health = number(args[1]);
            if (health == null || health < ShopkeeperController.MIN_MAX_HEALTH
                    || health > ShopkeeperController.MAX_MAX_HEALTH) {
                player.sendMessage("Maximum health must be between 1 and 1024 health points.");
            } else {
                controller.setMaxHealth(player, health);
            }
            return;
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("name")) {
            String name = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
            int length = name.codePointCount(0, name.length());
            if (name.isEmpty() || length > 32) {
                player.sendMessage("The shopkeeper name must contain between 1 and 32 characters.");
            } else {
                controller.setName(player, name);
            }
            return;
        }
        player.sendMessage(USAGE);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return List.of("price", "unprice", "radius", "speed", "health", "name", "info").stream()
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
        if (args.length == 2 && args[0].equalsIgnoreCase("radius")) {
            return List.of("1", "2.5", "5", "8", "16").stream()
                    .filter(value -> value.startsWith(args[1])).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("speed")) {
            return List.of("0.25", "0.5", "0.75", "1.0", "1.5", "2.0").stream()
                    .filter(value -> value.startsWith(args[1])).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("health")) {
            return List.of("20", "40", "80", "100", "200", "1024").stream()
                    .filter(value -> value.startsWith(args[1])).toList();
        }
        return List.of();
    }

    private static Material item(String value) {
        Material material = Material.matchMaterial(value);
        return material != null && material.isItem() && !material.isAir() ? material : null;
    }

    private static Double number(String value) {
        try {
            double number = Double.parseDouble(value);
            return Double.isFinite(number) ? number : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
