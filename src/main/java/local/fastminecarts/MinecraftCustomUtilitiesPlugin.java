package local.fastminecarts;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class MinecraftCustomUtilitiesPlugin extends JavaPlugin {
    private GhastController ghastController;
    private ElytraSlotController elytraSlot;
    private QuickOpenController quickOpen;
    private CraftCommandWorkbench craftCommandWorkbench;

    @Override
    public void onEnable() {
        MinecartSettings settings = new MinecartSettings(this);
        settings.installDefaults();
        settings.load();

        RailGeometry geometry = new RailGeometry(settings);
        MinecartController controller = new MinecartController(this, settings, geometry);
        Bukkit.getPluginManager().registerEvents(controller, this);
        ghastController = new GhastController(this);
        Bukkit.getPluginManager().registerEvents(ghastController, this);
        elytraSlot = new ElytraSlotController(this);
        Bukkit.getPluginManager().registerEvents(elytraSlot, this);
        quickOpen = new QuickOpenController(this);
        Bukkit.getPluginManager().registerEvents(quickOpen, this);
        craftCommandWorkbench = new CraftCommandWorkbench(this);
        Bukkit.getPluginManager().registerEvents(craftCommandWorkbench, this);
        registerCommand("minecartspeed", "Configure Minecraft Custom Utilities",
                new MinecartSpeedCommand(settings, controller, geometry));
        registerCommand("ghastspeed", "Configure ridden Happy Ghast speed",
                new GhastSpeedCommand(ghastController));
        registerCommand("elytraslot", "Open your additional Elytra slot",
                new ElytraSlotCommand(elytraSlot));
        registerCommand("quickopen", "Open a held shulker box",
                new QuickOpenCommand(quickOpen));
        registerCommand("killphantoms", "Kill nearby phantoms",
                new KillPhantomsCommand());
        registerCommand("shulkerfind", "Find items in shulker boxes in your inventory",
                new ShulkerFindCommand());
        registerCommand("craft", "Quick-craft using a command workbench",
                new CraftCommand(craftCommandWorkbench));
        registerCommand("mcu", "Show all Minecraft Custom Utilities commands",
                new HelpCommand());

        int changed = controller.applyToAllLoadedMinecarts();
        getLogger().info(String.format(Locale.ROOT,
                "Enabled. Target speed: %.2f blocks/sec, acceleration: %.2f blocks/sec^2, curve speed: %.2f blocks/sec. Updated %d loaded minecart(s).",
                settings.speedBlocksPerSecond(), settings.accelerationBlocksPerSecondSquared(),
                settings.curveSpeedBlocksPerSecond(), changed));
        getLogger().info("Ridden Happy Ghast acceleration enabled for "
                + ghastController.trackedGhasts() + " loaded Happy Ghast(s).");
        if (settings.speedBlocksPerSecond() > 100.0) {
            getLogger().warning("Minecart speeds above 100 blocks/sec can cause chunk-loading, collision, or visual problems. Use extreme speeds carefully.");
        }
    }

    @Override
    public void onDisable() {
        if (ghastController != null) {
            ghastController.shutdown();
        }
        if (elytraSlot != null) {
            elytraSlot.shutdown();
        }
        if (quickOpen != null) {
            quickOpen.shutdown();
        }
    }
}
