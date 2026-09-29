package dev.evoday.crates;

import dev.evoday.crates.crate.CrateBlocks;
import dev.evoday.crates.crate.CrateRegistry;
import dev.evoday.crates.storage.Database;
import dev.evoday.crates.storage.KeyRepo;
import dev.evoday.crates.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class EvoCrates extends JavaPlugin {

    private Database db;
    private Messages messages;
    private CrateRegistry crates;
    private CrateBlocks blocks;
    private CrateService service;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        try {
            db = new Database(this, getConfig().getConfigurationSection("storage"));
            db.sync(c -> {
                KeyRepo.createTables(c);
                return null;
            });
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Database is not available, plugin disabled", e);
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        crates = new CrateRegistry(this);
        crates.load();
        blocks = new CrateBlocks(this, crates);
        blocks.load();
        service = new CrateService(this);

        getServer().getPluginManager().registerEvents(new CrateListener(this), this);
        CratesCommand command = new CratesCommand(this);
        getCommand("crates").setExecutor(command);
        getCommand("crates").setTabCompleter(command);

        Bukkit.getOnlinePlayers().forEach(p -> service.loadKeys(p.getUniqueId()));
        Bukkit.getScheduler().runTaskTimer(this, blocks::refresh, 20L, 100L);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new KeysExpansion(this).register();
        }
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.shutdown();
        }
        if (blocks != null) {
            blocks.removeAllHolograms();
        }
        if (db != null) {
            db.close();
        }
    }

    public void reloadAll() {
        reloadConfig();
        messages.reload();
        crates.load();
        blocks.removeAllHolograms();
        blocks.refresh();
    }

    public Database db() {
        return db;
    }

    public Messages messages() {
        return messages;
    }

    public CrateRegistry crates() {
        return crates;
    }

    public CrateBlocks blocks() {
        return blocks;
    }

    public CrateService service() {
        return service;
    }
}
