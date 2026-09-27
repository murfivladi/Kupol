package dev.vlad.core.modules.tab;

import dev.vlad.core.VladCore;
import dev.vlad.core.module.Module;
import dev.vlad.core.util.Gradient;
import dev.vlad.core.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TAB: шапка/подвал с переменными и анимированным градиентным заголовком,
 * имена с префиксами LuckPerms, сортировка по группам (через команды scoreboard).
 * Настройки — в tab.yml.
 */
public final class TabModule extends Module implements Listener {

    private static final String TEAM_PREFIX = "vcTab";
    /** Имена/команды обновляем раз в столько обновлений шапки — они меняются редко. */
    private static final int NAMES_EVERY = 5;

    private final File file;
    private YamlConfiguration config;
    private BukkitTask task;
    private List<String> frames = new ArrayList<>();
    private int frame;
    private int tick;

    public TabModule(VladCore plugin) {
        super(plugin, "tab");
        this.file = new File(plugin.getDataFolder(), "tab.yml");
    }

    @Override
    protected void onEnable() {
        load();
        listen(this);
        start();
    }

    @Override
    protected void onReload() {
        load();
        if (task != null) {
            task.cancel();
        }
        start();
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
        }
        Set<Scoreboard> boards = new HashSet<>();
        boards.add(Bukkit.getScoreboardManager().getMainScoreboard());
        Bukkit.getOnlinePlayers().forEach(p -> boards.add(p.getScoreboard()));
        for (Scoreboard board : boards) {
            for (Team team : board.getTeams()) {
                if (team.getName().startsWith(TEAM_PREFIX)) {
                    team.unregister();
                }
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setPlayerListHeaderFooter("", "");
            p.setPlayerListName(null);
        }
    }

    private void load() {
        if (!file.exists()) {
            plugin.saveResource("tab.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        frames = Gradient.frames(config.getConfigurationSection("title"), plugin.getLogger());
    }

    private void start() {
        long every = Math.max(1, config.getLong("update-ticks", 4));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 1L, every);
    }

    // ---------------- анимация ----------------

    // ---------------- обновление ----------------

    private void update() {
        String title = frames.isEmpty() ? "" : frames.get(frame++ % frames.size());
        boolean names = tick++ % NAMES_EVERY == 0;
        String header = String.join("\n", config.getStringList("header"));
        String footer = String.join("\n", config.getStringList("footer"));
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Заголовок подставляем после цветов: в нём уже готовые §-коды.
            player.setPlayerListHeaderFooter(
                    plugin.placeholders().fill(player, header).replace("{title}", title),
                    plugin.placeholders().fill(player, footer).replace("{title}", title));
            if (names) {
                updateName(player);
            }
        }
    }

    private void updateName(Player player) {
        String name = Messages.color(plugin.placeholders().apply(player, config.getString("name-format", "{prefix}{name}{suffix}"))
                .replace("{prefix}", plugin.meta().prefix(player))
                .replace("{suffix}", plugin.meta().suffix(player))
                .replace("{name}", player.getDisplayName()));
        if (!name.equals(player.getPlayerListName())) {
            player.setPlayerListName(name);
        }

        // Сортировка: таб упорядочивает по имени команды — vcTab00 выше vcTab01.
        List<String> groups = config.getStringList("sort-groups");
        int index = groups.size();
        for (int i = 0; i < groups.size(); i++) {
            if (player.hasPermission("group." + groups.get(i))) {
                index = i;
                break;
            }
        }
        String teamName = String.format("%s%02d", TEAM_PREFIX, index);
        // У игроков может быть личный скорборд (боковая панель) — команды сортировки
        // должны быть на каждом, иначе таб у такого игрока будет не отсортирован.
        Set<Scoreboard> boards = new HashSet<>();
        boards.add(Bukkit.getScoreboardManager().getMainScoreboard());
        Bukkit.getOnlinePlayers().forEach(p -> boards.add(p.getScoreboard()));
        for (Scoreboard board : boards) {
            placeInTeam(board, player.getName(), teamName);
        }
    }

    private static void placeInTeam(Scoreboard board, String entry, String teamName) {
        Team current = board.getEntryTeam(entry);
        if (current != null && current.getName().equals(teamName)) {
            return;
        }
        if (current != null && current.getName().startsWith(TEAM_PREFIX)) {
            current.removeEntry(entry);
        } else if (current != null) {
            return; // игрок в команде другого плагина — не трогаем
        }
        Team team = board.getTeam(teamName);
        if (team == null) {
            team = board.registerNewTeam(teamName);
        }
        team.addEntry(entry);
    }

    /** Новый личный скорборд игрока: перенести на него сортировку всех онлайн. */
    public void syncBoard(Scoreboard board) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Team main = Bukkit.getScoreboardManager().getMainScoreboard().getEntryTeam(p.getName());
            if (main != null && main.getName().startsWith(TEAM_PREFIX)) {
                placeInTeam(board, p.getName(), main.getName());
            }
        }
    }

    // ---------------- события ----------------

    /** MONITOR — после модуля chat, который выставляет /nick. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        updateName(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Set<Scoreboard> boards = new HashSet<>();
        boards.add(Bukkit.getScoreboardManager().getMainScoreboard());
        Bukkit.getOnlinePlayers().forEach(p -> boards.add(p.getScoreboard()));
        for (Scoreboard board : boards) {
            Team team = board.getEntryTeam(event.getPlayer().getName());
            if (team != null && team.getName().startsWith(TEAM_PREFIX)) {
                team.removeEntry(event.getPlayer().getName());
            }
        }
    }
}
