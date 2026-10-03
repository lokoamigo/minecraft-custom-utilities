package local.fastminecarts;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

final class QuickOpenCommand implements BasicCommand {
    private final QuickOpenController controller;

    QuickOpenCommand(QuickOpenController controller) {
        this.controller = controller;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("This command can only be used by a player.");
            return;
        }
        if (args.length != 0) {
            player.sendMessage("Usage: /quickopen");
            return;
        }
        controller.open(player);
    }
}
