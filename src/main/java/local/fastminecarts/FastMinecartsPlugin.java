package local.fastminecarts;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class FastMinecartsPlugin extends JavaPlugin {
    private GhastController ghastController;

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
        registerCommand("minecartspeed", "Configure FastMinecarts",
                new MinecartSpeedCommand(settings, controller, geometry));
        registerCommand("ghastspeed", "Configure ridden Happy Ghast speed",
                new GhastSpeedCommand(ghastController));

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
    }
}
