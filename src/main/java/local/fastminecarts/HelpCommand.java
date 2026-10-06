package local.fastminecarts;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;

import java.util.Collection;
import java.util.List;

final class HelpCommand implements BasicCommand {
    private static final List<String> HELP = List.of(
            "Minecraft Custom Utilities commands:",
            "/mcu help - Show this command list.",
            "/elytraslot - Open your additional Elytra equipment slot.",
            "/quickopen - Open the shulker box in your main hand or offhand.",
            "/craft <item> [count] - Quick-craft using a craft-command-workbench (or right-click it).",
            "/shopkeeper price <item> <emeralds> - Price an item for the selected shopkeeper.",
            "/shopkeeper unprice <item> - Remove an item from the selected shopkeeper's offers.",
            "/shopkeeper info - List the selected shopkeeper's linked chests and prices.",
            "/shulkerfind <item> [quantity] [<item> [quantity] ...] - Move up to the requested amounts from shulker boxes.",
            "/shulkerfind locate <item> [quantity] [<item> [quantity] ...] - Find items without moving them.",
            "/killphantoms [radius] - Kill nearby phantoms. (admin)",
            "/minecartspeed - Show the current minecart settings. (admin)",
            "/minecartspeed <speed> - Set the minecart target speed. (admin)",
            "/minecartspeed acceleration|accel <value> - Set minecart acceleration. (admin)",
            "/minecartspeed curvespeed|curve <value> - Set minecart curve speed. (admin)",
            "/minecartspeed reload - Reload minecart settings. (admin)",
            "/ghastspeed - Show the current Happy Ghast settings. (admin)",
            "/ghastspeed <speed> - Set the ridden Happy Ghast maximum speed. (admin)",
            "/ghastspeed acceleration|accel <value> - Set Happy Ghast acceleration. (admin)"
    );

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length > 1 || (args.length == 1 && !args[0].equalsIgnoreCase("help"))) {
            sender.sendMessage("Usage: /mcu [help]");
            return;
        }
        HELP.forEach(sender::sendMessage);
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[0].toLowerCase();
        return "help".startsWith(prefix) ? List.of("help") : List.of();
    }
}
