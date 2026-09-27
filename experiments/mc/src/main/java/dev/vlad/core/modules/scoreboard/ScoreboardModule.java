package dev.vlad.core.modules.scoreboard;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.module.Module;
import dev.vlad.core.modules.tab.TabModule;
import dev.vlad.core.util.Gradient;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Боковая панель: у каждого игрока свой скорборд. Строки — это команды (teams) с
 * невидимыми уникальными записями; меняется только префикс команды, поэтому не мерцает.
 */
public final class ScoreboardModule extends Module implements Listener {

    private static final int MAX_LINES = 15;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final String HIDDEN = "scoreboard.hidden";

    private final File file;
    private final Map<UUID, Board> boards = new HashMap<>();
    private YamlConfiguration config;
    private List<String> frames = Collections.singletonList("");
    private BukkitTask task;
    private int frame;

    public ScoreboardModule(VladCore plugin) {
        super(plugin, "scoreboard");
        this.file = new File(plugin.getDataFolder(), "scoreboard.yml");
    }

    @Override
    protected void onEnable() {
        load();
        listen(this);
        command(new ToggleCommand());
        start();
    }

    @Override
    protected void onReload() {
        if (task != null) {
            task.cancel();
        }
        load();
        // Число строк могло поменяться — пересоздаём панели.
        new ArrayList<>(boards.keySet()).forEach(id -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                remove(p);
            }
        });
        start();
    }

    @Override
    protected void onDisable() {
        if (task != null) {
            task.cancel();
        }
        new ArrayList<>(boards.keySet()).forEach(id -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                remove(p);
            }
        });
        boards.clear();
    }

    private void load() {
        if (!file.exists()) {
            plugin.saveResource("scoreboard.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(file);
        frames = Gradient.frames(config.getConfigurationSection("title"), plugin.getLogger());
    }

    private void start() {
        long every = Math.max(1, config.getLong("update-ticks", 10));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::update, 1L, every);
    }

    private boolean wants(Player player) {
        return plugin.players().get(player.getUniqueId()).getString(HIDDEN) == null
                && !config.getStringList("disabled-worlds").contains(player.getWorld().getName());
    }

    private void update() {
        String title = frames.get(frame++ % frames.size());
        List<String> lines = config.getStringList("lines");
        if (lines.size() > MAX_LINES) {
            lines = lines.subList(0, MAX_LINES);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!wants(player)) {
                remove(player);
                continue;
            }
            Board board = boards.get(player.getUniqueId());
            if (board == null) {
                board = new Board(player, lines.size());
                boards.put(player.getUniqueId(), board);
            }
            board.update(title, lines);
        }
    }

    private void remove(Player player) {
        if (boards.remove(player.getUniqueId()) != null) {
            player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        }
    }

    // ---------------- панель ----------------

    private final class Board {
        private final Player player;
        private final Scoreboard scoreboard;
        private final Objective objective;
        private final List<Team> rows = new ArrayList<>();
        private String lastTitle;
        private final String[] lastLines;

        Board(Player player, int size) {
            this.player = player;
            this.lastLines = new String[size];
            this.scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
            this.objective = scoreboard.registerNewObjective("vcsb", "dummy", " ");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            ChatColor[] codes = ChatColor.values();
            for (int i = 0; i < size; i++) {
                // Уникальная невидимая запись строки: §0§r, §1§r, ...
                String entry = codes[i].toString() + ChatColor.RESET;
                Team team = scoreboard.registerNewTeam("vcsb" + i);
                team.addEntry(entry);
                objective.getScore(entry).setScore(size - i);
                rows.add(team);
            }
            // Сортировка таба живёт в командах — переносим её на новый скорборд.
            for (Module m : plugin.modules().all()) {
                if (m instanceof TabModule && m.isEnabled()) {
                    ((TabModule) m).syncBoard(scoreboard);
                }
            }
            player.setScoreboard(scoreboard);
        }

        void update(String title, List<String> lines) {
            // Через компоненты Adventure: у текстовых setDisplayName/setPrefix лимит 128 символов,
            // а градиент с HEX-цветом на каждую букву его превышает.
            if (!title.equals(lastTitle)) {
                objective.displayName(LEGACY.deserialize(title));
                lastTitle = title;
            }
            for (int i = 0; i < rows.size() && i < lines.size(); i++) {
                String text = plugin.placeholders().fill(player, lines.get(i));
                if (!text.equals(lastLines[i])) {
                    rows.get(i).prefix(LEGACY.deserialize(text));
                    lastLines[i] = text;
                }
            }
        }
    }

    // ---------------- события и команда ----------------

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        boards.remove(event.getPlayer().getUniqueId());
    }

    private final class ToggleCommand extends ModuleCommand {
        ToggleCommand() {
            super(ScoreboardModule.this.plugin, "scoreboard", "vcore.scoreboard", "/sb", "sb");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            boolean hide = plugin.players().get(player.getUniqueId()).getString(HIDDEN) == null;
            plugin.players().get(player.getUniqueId()).set(HIDDEN, hide ? "true" : null);
            if (hide) {
                remove(player);
            }
            plugin.messages().send(player, hide ? "scoreboard.hidden" : "scoreboard.shown");
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return Collections.emptyList();
        }
    }
}
