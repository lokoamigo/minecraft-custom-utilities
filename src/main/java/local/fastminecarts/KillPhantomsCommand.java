package local.fastminecarts;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class KillPhantomsCommand implements BasicCommand {
    private static final String PERMISSION = "fastminecarts.admin";
    private static final double DEFAULT_RADIUS = 64.0;
    private static final double MAX_RADIUS = 256.0;

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("This command can only be used by a player.");
            return;
        }
        if (args.length > 1) {
            player.sendMessage("Usage: /killphantoms [radius]");
            return;
        }

        double radius = DEFAULT_RADIUS;
        if (args.length == 1) {
            try {
                radius = Double.parseDouble(args[0]);
            } catch (NumberFormatException exception) {
                player.sendMessage("Radius must be a number.");
                return;
            }
            if (!Double.isFinite(radius) || radius <= 0.0 || radius > MAX_RADIUS) {
                player.sendMessage(String.format(Locale.ROOT,
                        "Radius must be above 0 and at most %.0f blocks.", MAX_RADIUS));
                return;
            }
        }

        int killed = 0;
        for (Phantom phantom : player.getWorld().getNearbyEntitiesByType(
                Phantom.class, player.getLocation(), radius, radius, radius)) {
            if (!phantom.isDead()) {
                phantom.setHealth(0.0);
                killed++;
            }
        }
        player.sendMessage(String.format(Locale.ROOT,
                "Killed %d phantom%s within %.0f blocks.", killed, killed == 1 ? "" : "s", radius));
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length > 1) {
            return List.of();
        }
        String prefix = args.length == 0 ? "" : args[0];
        return List.of("32", "64", "128", "256").stream()
                .filter(value -> value.startsWith(prefix)).toList();
    }
}
