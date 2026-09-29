package dev.evoday.gate;

import dev.evoday.gate.antibot.AntiBot;
import dev.evoday.gate.captcha.CaptchaImage;
import dev.evoday.gate.captcha.CaptchaWall;
import dev.evoday.gate.command.AdminCommand;
import dev.evoday.gate.command.PlayerCommands;
import dev.evoday.gate.listener.JoinListener;
import dev.evoday.gate.listener.LockListener;
import dev.evoday.gate.session.AuthManager;
import dev.evoday.gate.storage.AccountRepo;
import dev.evoday.gate.storage.Database;
import dev.evoday.gate.util.Messages;
import dev.evoday.gate.util.PasswordLogFilter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

public final class EvoGate extends JavaPlugin {

    private Database db;
    private Messages messages;
    private CaptchaWall captcha;
    private AntiBot antiBot;
    private AuthManager auth;
    private PasswordLogFilter logFilter;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);

        try {
            db = new Database(this, getConfig().getConfigurationSection("storage"));
            db.sync(c -> {
                AccountRepo.createTable(c);
                return null;
            });
        } catch (Exception e) {
            // без базы пускать игроков нельзя - выключаем сервер от греха подальше
            getLogger().log(Level.SEVERE, "Database is not available, shutting down the server", e);
            Bukkit.shutdown();
            return;
        }

        captcha = new CaptchaWall(this);
        captcha.init();
        if (!CaptchaImage.usesTtf()) {
            getLogger().warning("Fonts are not supported by this Java, captcha uses the built-in pixel font");
        }
        antiBot = new AntiBot(this);
        auth = new AuthManager(this);
        Bukkit.getOnlinePlayers().forEach(auth::adopt);

        logFilter = new PasswordLogFilter();
        logFilter.install();

        getServer().getPluginManager().registerEvents(new JoinListener(this), this);
        getServer().getPluginManager().registerEvents(new LockListener(this), this);

        PlayerCommands playerCommands = new PlayerCommands(this);
        for (String name : new String[]{"register", "login", "changepassword", "logout"}) {
            getCommand(name).setExecutor(playerCommands);
            getCommand(name).setTabCompleter(playerCommands);
        }
        AdminCommand admin = new AdminCommand(this);
        getCommand("evogate").setExecutor(admin);
        getCommand("evogate").setTabCompleter(admin);

        getLogger().info("Storage: " + (db.isMysql() ? "MySQL" : "SQLite"));
    }

    @Override
    public void onDisable() {
        if (auth != null) {
            auth.shutdown();
        }
        if (captcha != null) {
            captcha.hideAll();
        }
        if (logFilter != null) {
            logFilter.uninstall();
        }
        if (db != null) {
            db.close();
        }
    }

    public Database db() {
        return db;
    }

    public Messages messages() {
        return messages;
    }

    public AntiBot antiBot() {
        return antiBot;
    }

    public CaptchaWall captcha() {
        return captcha;
    }

    public AuthManager auth() {
        return auth;
    }
}
