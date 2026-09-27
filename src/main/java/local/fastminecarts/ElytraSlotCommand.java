package local.fastminecarts;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

final class ElytraSlotCommand implements BasicCommand {
    private final ElytraSlotController controller;

    ElytraSlotCommand(ElytraSlotController controller) {
        this.controller = controller;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            source.getSender().sendMessage("This command can only be used by a player.");
            return;
        }
        controller.open(player);
    }
}
