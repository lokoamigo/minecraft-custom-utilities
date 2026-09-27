package local.fastminecarts;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class GhastSpeedCommand implements BasicCommand {
    private static final String PERMISSION = "fastminecarts.admin";
    private final GhastController controller;

    GhastSpeedCommand(GhastController controller) {
        this.controller = controller;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (args.length == 0) {
            sendStatus(sender);
        } else if (args.length == 1) {
            setSpeed(sender, args[0]);
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("acceleration")
                || args[0].equalsIgnoreCase("accel"))) {
            setAcceleration(sender, args[1]);
        } else {
            sendUsage(sender);
        }
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 0) {
            return List.of("acceleration", "40", "60", "80", "100");
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return List.of("acceleration", "40", "60", "80", "100").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("acceleration")
                || args[0].equalsIgnoreCase("accel"))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return List.of("2", "4", "8", "12", "20").stream()
                    .filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }

    private void setSpeed(CommandSender sender, String input) {
        Double value = parse(sender, input, "Speed");
        if (value == null || !inRange(sender, value, GhastController.MAX_ALLOWED_SPEED_BPS,
                "Speed", "blocks/sec")) {
            return;
        }
        controller.setSpeedBlocksPerSecond(value);
        sender.sendMessage(String.format(Locale.ROOT,
                "Ridden Happy Ghast maximum speed set to %.2f blocks/sec.", value));
    }

    private void setAcceleration(CommandSender sender, String input) {
        Double value = parse(sender, input, "Acceleration");
        if (value == null || !inRange(sender, value, GhastController.MAX_ALLOWED_ACCELERATION_BPS2,
                "Acceleration", "blocks/sec^2")) {
            return;
        }
        controller.setAccelerationBlocksPerSecondSquared(value);
        sender.sendMessage(String.format(Locale.ROOT,
                "Ridden Happy Ghast acceleration set to %.2f blocks/sec^2.", value));
    }

    private static Double parse(CommandSender sender, String input, String name) {
        try {
            double value = Double.parseDouble(input);
            if (Double.isFinite(value)) {
                return value;
            }
        } catch (NumberFormatException ignored) {
        }
        sender.sendMessage(name + " must be a finite number.");
        return null;
    }

    private static boolean inRange(CommandSender sender, double value, double maximum,
                                   String name, String unit) {
        if (value > 0.0 && value <= maximum) {
            return true;
        }
        sender.sendMessage(String.format(Locale.ROOT,
                "%s must be above 0 and at most %.0f %s.", name, maximum, unit));
        return false;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(String.format(Locale.ROOT,
                "Ridden Happy Ghast speed: %.2f blocks/sec | Acceleration: %.2f blocks/sec^2",
                controller.speedBlocksPerSecond(), controller.accelerationBlocksPerSecondSquared()));
        sendUsage(sender);
    }

    private static void sendUsage(CommandSender sender) {
        sender.sendMessage("Usage: /ghastspeed <speed|acceleration <value>>");
    }
}
