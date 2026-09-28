package dev.vlad.core.modules.motd;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.destroystokyo.paper.profile.PlayerProfile;
import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.module.Module;
import dev.vlad.core.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.util.CachedServerIcon;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * MOTD в списке серверов: несколько вариантов с градиентами и плейсхолдерами,
 * подсказка при наведении, свой максимум онлайна, случайные иконки, режим техработ.
 */
public final class MotdModule extends Module implements Listener {

    private static final String BYPASS = "vcore.maintenance.bypass";
    /** Ширина области MOTD в пикселях шрифта Minecraft — для центрирования. */
    private static final int MOTD_WIDTH = 270;

    private final File file;
    private final File iconsDir;
    private final File maintenanceFlag;
    private YamlConfiguration config;
    private final List<CachedServerIcon> icons = new ArrayList<>();
    private int next;

    public MotdModule(VladCore plugin) {
        super(plugin, "motd");
        this.file = new File(plugin.getDataFolder(), "motd.yml");
        this.iconsDir = new File(plugin.getDataFolder(), "icons");
        this.maintenanceFlag = new File(plugin.getDataFolder(), "maintenance.flag");
    }

    @Override
    protected void onEnable() {
        load();
        listen(this);
        command(new MaintenanceCommand());
        placeholder("maintenance", p -> yesNo(maintenance()));
    }

    @Override
    protected void onReload() {
        load();
    }

    private void load() {
        if (!file.exists()) {
            plugin.saveResource("motd.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        icons.clear();
        iconsDir.mkdirs();
        File[] files = iconsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".png"));
        if (files != null) {
            for (File f : files) {
                try {
                    icons.add(Bukkit.loadServerIcon(f));
                } catch (Exception e) {
                    plugin.getLogger().warning("Иконка " + f.getName() + " не подошла (нужен PNG 64×64): " + e.getMessage());
                }
            }
        }
    }

    private boolean maintenance() {
        return maintenanceFlag.exists();
    }

    // ---------------- список серверов ----------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(PaperServerListPingEvent event) {
        int online = onlineCount();
        int max = maxPlayers(online);
        event.setNumPlayers(online);
        event.setMaxPlayers(max);

        List<String> lines = pickMotd();
        boolean center = config.getBoolean("center", true);
        event.setMotd(lines.stream()
                .map(l -> render(l, online, max))
                .map(l -> center ? center(l) : l)
                .collect(Collectors.joining("\n")));

        List<String> hover = config.getStringList("hover");
        if (!hover.isEmpty()) {
            List<PlayerProfile> sample = event.getPlayerSample();
            sample.clear();
            for (String line : hover) {
                sample.add(Bukkit.createProfile(UUID.randomUUID(), render(line, online, max)));
            }
        } else if (config.getBoolean("hide-vanished", true)) {
            event.getPlayerSample().removeIf(p -> {
                Player player = p.getId() == null ? null : Bukkit.getPlayer(p.getId());
                return player != null && isVanished(player);
            });
        }

        if (!icons.isEmpty()) {
            event.setServerIcon(icons.get(ThreadLocalRandom.current().nextInt(icons.size())));
        }
    }

    private List<String> pickMotd() {
        if (maintenance()) {
            return config.getStringList("maintenance.motd");
        }
        List<?> motds = config.getList("motds", Collections.emptyList());
        if (motds.isEmpty()) {
            return Collections.singletonList(Bukkit.getMotd());
        }
        int index = "order".equalsIgnoreCase(config.getString("mode"))
                ? Math.floorMod(next++, motds.size())
                : ThreadLocalRandom.current().nextInt(motds.size());
        Object motd = motds.get(index);
        if (motd instanceof List) {
            return ((List<?>) motd).stream().map(String::valueOf).limit(2).collect(Collectors.toList());
        }
        return Arrays.asList(String.valueOf(motd).split("\n", 2));
    }

    private String render(String text, int online, int max) {
        text = text.replace("{online}", String.valueOf(online)).replace("{max}", String.valueOf(max));
        return Messages.color(plugin.placeholders().apply(null, text));
    }

    private int onlineCount() {
        if (!config.getBoolean("hide-vanished", true)) {
            return Bukkit.getOnlinePlayers().size();
        }
        return (int) Bukkit.getOnlinePlayers().stream().filter(p -> !isVanished(p)).count();
    }

    /** Ваниш — через плейсхолдер модуля vanish (если он выключен — никто не невидим). */
    private boolean isVanished(Player player) {
        String value = plugin.placeholders().resolve(player, "vanished");
        return value != null && value.equals(plugin.messages().get("placeholder.yes"));
    }

    private int maxPlayers(int online) {
        String mode = config.getString("max-players", "real").trim().toLowerCase();
        try {
            if (mode.startsWith("online+")) {
                return online + Integer.parseInt(mode.substring("online+".length()).trim());
            }
            if (!mode.equals("real")) {
                return Integer.parseInt(mode);
            }
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("motd.yml: непонятный max-players: " + mode);
        }
        return Bukkit.getMaxPlayers();
    }

    // ---------------- центрирование ----------------

    /** Добавить пробелы слева, чтобы строка оказалась по центру (по ширине шрифта Minecraft). */
    private static String center(String line) {
        int width = 0;
        boolean bold = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '§' && i + 1 < line.length()) {
                char code = Character.toLowerCase(line.charAt(++i));
                if (code == 'l') {
                    bold = true;
                } else if (code == 'r' || "0123456789abcdefx".indexOf(code) >= 0) {
                    bold = false;
                }
                continue;
            }
            width += charWidth(c) + (bold && c != ' ' ? 1 : 0) + 1;
        }
        int spaces = Math.max(0, (MOTD_WIDTH - width) / 2 / 4); // пробел = 3 + 1 пиксель
        char[] pad = new char[spaces];
        Arrays.fill(pad, ' ');
        return new String(pad) + line;
    }

    private static int charWidth(char c) {
        switch (c) {
            case 'i': case '!': case ',': case '.': case '\'': case ':': case ';': case '|':
                return 1;
            case 'l': case '`':
                return 2;
            case 'I': case 't': case '[': case ']': case ' ':
                return 3;
            case 'f': case 'k': case '(': case ')': case '{': case '}': case '<': case '>': case '"': case '*':
                return 4;
            case '@': case '~':
                return 6;
            default:
                return 5;
        }
    }

    // ---------------- техработы ----------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        if (maintenance() && !event.getPlayer().hasPermission(BYPASS)) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, kickMessage());
        }
    }

    private String kickMessage() {
        return Messages.color(config.getString("maintenance.kick-message", "&cТехнические работы."));
    }

    private final class MaintenanceCommand extends ModuleCommand {
        MaintenanceCommand() {
            super(MotdModule.this.plugin, "maintenance", "vcore.maintenance", "/maintenance [on|off]", "techworks");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            boolean on = args.length > 0 ? args[0].equalsIgnoreCase("on") : !maintenance();
            try {
                if (on) {
                    maintenanceFlag.createNewFile();
                } else if (maintenanceFlag.exists() && !maintenanceFlag.delete()) {
                    throw new IOException("не удалось удалить " + maintenanceFlag);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("maintenance: " + e.getMessage());
                return;
            }
            if (on) {
                String message = kickMessage();
                for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                    if (!p.hasPermission(BYPASS)) {
                        p.kickPlayer(message);
                    }
                }
            }
            plugin.messages().send(sender, on ? "motd.maintenance-on" : "motd.maintenance-off");
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 ? Arrays.asList("on", "off") : Collections.emptyList();
        }
    }
}
