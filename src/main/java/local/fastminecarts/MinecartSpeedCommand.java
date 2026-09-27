package local.fastminecarts;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class MinecartSpeedCommand implements BasicCommand {
    static final String PERMISSION = "fastminecarts.admin";
    private final MinecartSettings settings;
    private final MinecartController controller;
    private final RailGeometry geometry;

    MinecartSpeedCommand(MinecartSettings settings, MinecartController controller, RailGeometry geometry) {
        this.settings = settings;
        this.controller = controller;
        this.geometry = geometry;
    }

    @Override public void execute(CommandSourceStack source, String[] args) { handle(source.getSender(), args); }
    @Override public String permission() { return PERMISSION; }
    @Override public Collection<String> suggest(CommandSourceStack source, String[] args) { return suggestions(args); }

    private void handle(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sendStatus(sender);
        } else if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            settings.reload();
            geometry.clearCache();
            int changed = controller.applyToAllLoadedMinecarts();
            sender.sendMessage(String.format(Locale.ROOT,
                    "Minecraft Custom Utilities reloaded. Speed: %.2f blocks/sec, acceleration: %.2f blocks/sec^2, curve speed: %.2f blocks/sec. Updated %d loaded minecart(s).",
                    settings.speedBlocksPerSecond(), settings.accelerationBlocksPerSecondSquared(),
                    settings.curveSpeedBlocksPerSecond(), changed));
        } else if (args[0].equalsIgnoreCase("acceleration") || args[0].equalsIgnoreCase("accel")) {
            setAcceleration(sender, args);
        } else if (args[0].equalsIgnoreCase("curvespeed") || args[0].equalsIgnoreCase("curve")) {
            setCurveSpeed(sender, args);
        } else {
            setSpeed(sender, args);
        }
    }

    private void setAcceleration(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /minecartspeed acceleration <blocks/sec^2>");
            return;
        }
        Double value = parseNumber(sender, args[1], "Acceleration");
        if (value == null || !inRange(sender, value, MinecartSettings.MAX_ALLOWED_ACCELERATION_BPS2,
                "Acceleration", "blocks/sec^2")) return;
        settings.setAccelerationBlocksPerSecondSquared(value);
        geometry.clearCache();
        sender.sendMessage(String.format(Locale.ROOT, "Minecart acceleration set to %.2f blocks/sec^2.", value));
    }

    private void setCurveSpeed(CommandSender sender, String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Usage: /minecartspeed curvespeed <blocks/sec>");
            return;
        }
        Double value = parseNumber(sender, args[1], "Curve speed");
        if (value == null || !inRange(sender, value, MinecartSettings.MAX_ALLOWED_SPEED_BPS,
                "Curve speed", "blocks/sec")) return;
        settings.setCurveSpeedBlocksPerSecond(value);
        geometry.clearCache();
        sender.sendMessage(String.format(Locale.ROOT, "Minecart curve speed set to %.2f blocks/sec.", value));
    }

    private void setSpeed(CommandSender sender, String[] args) {
        if (args.length != 1) {
            sendUsage(sender);
            return;
        }
        Double value = parseNumber(sender, args[0], "Speed");
        if (value == null || !inRange(sender, value, MinecartSettings.MAX_ALLOWED_SPEED_BPS,
                "Speed", "blocks/sec")) return;
        settings.setSpeedBlocksPerSecond(value);
        geometry.clearCache();
        int changed = controller.applyToAllLoadedMinecarts();
        sender.sendMessage(String.format(Locale.ROOT,
                "Minecart target speed set to %.2f blocks/sec. Updated %d loaded minecart(s).", value, changed));
        if (value > 100.0) sender.sendMessage(
                "Warning: speeds above 100 blocks/sec may cause chunk-loading, collision, or visual problems.");
    }

    private boolean inRange(CommandSender sender, double value, double maximum, String name, String unit) {
        if (value >= 0.0 && value <= maximum) return true;
        sender.sendMessage(String.format(Locale.ROOT,
                "%s must be between 0 and %.0f %s.", name, maximum, unit));
        return false;
    }

    private Double parseNumber(CommandSender sender, String input, String name) {
        final double value;
        try {
            value = Double.parseDouble(input);
        } catch (NumberFormatException exception) {
            sender.sendMessage(name + " must be a number.");
            return null;
        }
        if (!Double.isFinite(value)) {
            sender.sendMessage(name + " must be a finite number.");
            return null;
        }
        return value;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(String.format(Locale.ROOT,
                "Minecart speed: %.2f blocks/sec | Acceleration: %.2f blocks/sec^2 | Curve speed: %.2f blocks/sec | Slopes: vanilla | Boost all rails: %s | Slow when empty: %s",
                settings.speedBlocksPerSecond(), settings.accelerationBlocksPerSecondSquared(),
                settings.curveSpeedBlocksPerSecond(), settings.boostOnAllRails(), settings.slowWhenEmpty()));
        sendUsage(sender);
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("Usage: /minecartspeed <speed|reload|acceleration <value>|curvespeed <value>>");
    }

    private Collection<String> suggestions(String[] args) {
        List<String> options;
        String input;
        if (args.length == 0) {
            return List.of("reload", "acceleration", "curvespeed", "8", "16", "24", "32", "40", "64", "100");
        } else if (args.length == 1) {
            options = List.of("reload", "acceleration", "curvespeed", "8", "16", "24", "32", "40", "64", "100");
            input = args[0];
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("acceleration")
                || args[0].equalsIgnoreCase("accel"))) {
            options = List.of("20", "40", "80", "160", "320");
            input = args[1];
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("curvespeed")
                || args[0].equalsIgnoreCase("curve"))) {
            options = List.of("8", "10", "12", "14", "16");
            input = args[1];
        } else {
            return List.of();
        }
        String prefix = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) if (option.toLowerCase(Locale.ROOT).startsWith(prefix)) matches.add(option);
        return matches;
    }
}
