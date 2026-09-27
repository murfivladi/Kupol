package dev.vlad.core;

import dev.vlad.core.command.CoreCommand;
import dev.vlad.core.gui.MenuListener;
import dev.vlad.core.module.ModuleManager;
import dev.vlad.core.modules.auth.AuthModule;
import dev.vlad.core.modules.basics.BasicsModule;
import dev.vlad.core.modules.chat.ChatModule;
import dev.vlad.core.modules.economy.EconomyModule;
import dev.vlad.core.modules.homes.HomesModule;
import dev.vlad.core.modules.menu.MainMenuModule;
import dev.vlad.core.modules.moderation.JailModule;
import dev.vlad.core.modules.moderation.ModerationModule;
import dev.vlad.core.modules.scoreboard.ScoreboardModule;
import dev.vlad.core.modules.tab.TabModule;
import dev.vlad.core.modules.teleport.TeleportModule;
import dev.vlad.core.modules.vanish.VanishModule;
import dev.vlad.core.modules.warps.WarpsModule;
import dev.vlad.core.service.Placeholders;
import dev.vlad.core.service.PlayerMeta;
import dev.vlad.core.service.Teleporter;
import dev.vlad.core.storage.PlayerStore;
import dev.vlad.core.util.ConfigUpdater;
import dev.vlad.core.util.Messages;
import org.bukkit.plugin.java.JavaPlugin;

public final class VladCore extends JavaPlugin {

    private Messages messages;
    private ModuleManager modules;
    private PlayerStore players;
    private Teleporter teleporter;
    private PlayerMeta meta;
    private Placeholders placeholders;

    @Override
    public void onEnable() {
        ConfigUpdater.update(this, "config.yml");
        ConfigUpdater.update(this, "messages.yml");
        reloadConfig();
        messages = new Messages(this);
        players = new PlayerStore(this);
        teleporter = new Teleporter(this);
        meta = new PlayerMeta();
        placeholders = new Placeholders(this);
        placeholders.register("version", p -> getDescription().getVersion());
        getServer().getPluginManager().registerEvents(new MenuListener(), this);

        modules = new ModuleManager(this);
        // Новые модули добавляются сюда одной строкой.
        modules.register(new AuthModule(this));
        modules.register(new BasicsModule(this));
        modules.register(new TeleportModule(this));
        modules.register(new HomesModule(this));
        modules.register(new WarpsModule(this));
        modules.register(new EconomyModule(this));
        modules.register(new ModerationModule(this));
        modules.register(new JailModule(this));
        modules.register(new VanishModule(this));
        modules.register(new ChatModule(this));
        modules.register(new MainMenuModule(this));
        modules.register(new TabModule(this));
        modules.register(new ScoreboardModule(this));
        modules.enableAll();

        getCommand("vcore").setExecutor(new CoreCommand(this));
        getLogger().info("VladCore включён, модулей активно: " + modules.enabledCount());
    }

    @Override
    public void onDisable() {
        if (modules != null) {
            modules.disableAll();
        }
        if (teleporter != null) {
            teleporter.cancelAll();
        }
        if (players != null) {
            players.saveAll();
        }
    }

    public void reload() {
        reloadConfig();
        messages.reload();
        modules.reloadAll();
    }

    public Messages messages() {
        return messages;
    }

    public ModuleManager modules() {
        return modules;
    }

    public PlayerStore players() {
        return players;
    }

    public Teleporter teleporter() {
        return teleporter;
    }

    public PlayerMeta meta() {
        return meta;
    }

    public Placeholders placeholders() {
        return placeholders;
    }
}
