package dev.vlad.core.service;

import dev.vlad.core.VladCore;
import dev.vlad.core.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Плейсхолдеры в обе стороны.
 * <ul>
 *   <li>Наши: модули регистрируют %vladcore_&lt;имя&gt;% ({@link #register}) —
 *       через PlaceholderAPI их видят другие плагины.</li>
 *   <li>Чужие: {@link #apply} подставляет любые %...% PlaceholderAPI в наши тексты.</li>
 * </ul>
 * Без PlaceholderAPI {@link #apply} подставляет только наши %vladcore_...%.
 */
public final class Placeholders {

    private final VladCore plugin;
    private final boolean papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
    /** Точные имена: "balance" → значение. */
    private final Map<String, Function<OfflinePlayer, String>> exact = new ConcurrentHashMap<>();
    /** По префиксу с аргументом: "baltop_name_" + "3". */
    private final Map<String, BiFunction<OfflinePlayer, String, String>> prefixed = new ConcurrentHashMap<>();

    public Placeholders(VladCore plugin) {
        this.plugin = plugin;
        if (papi) {
            PapiBridge.registerExpansion(plugin, this);
            plugin.getLogger().info("PlaceholderAPI найден — %vladcore_...% доступны другим плагинам");
        }
    }

    public boolean papiAvailable() {
        return papi;
    }

    public void register(String name, Function<OfflinePlayer, String> value) {
        exact.put(name.toLowerCase(), value);
    }

    public void registerPrefix(String prefix, BiFunction<OfflinePlayer, String, String> value) {
        prefixed.put(prefix.toLowerCase(), value);
    }

    public void unregister(String name) {
        exact.remove(name.toLowerCase());
        prefixed.remove(name.toLowerCase());
    }

    /** Значение %vladcore_&lt;params&gt;%, либо null, если такого нет. */
    public String resolve(OfflinePlayer player, String params) {
        String key = params.toLowerCase();
        Function<OfflinePlayer, String> fn = exact.get(key);
        if (fn != null) {
            return fn.apply(player);
        }
        // Длинный префикс важнее короткого: baltop_name_ раньше baltop_.
        String best = null;
        for (String prefix : prefixed.keySet()) {
            if (key.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best == null ? null : prefixed.get(best).apply(player, params.substring(best.length()));
    }

    /** Все зарегистрированные имена — для /vcore placeholders. */
    public Set<String> names() {
        Map<String, Boolean> all = new TreeMap<>();
        exact.keySet().forEach(k -> all.put(k, true));
        prefixed.keySet().forEach(k -> all.put(k + "<N>", true));
        return all.keySet();
    }

    /**
     * Подставить плейсхолдеры в текст администратора (не игрока! — иначе игрок
     * сможет вписать в чат %чужой_плейсхолдер% и узнать лишнее).
     */
    public String apply(OfflinePlayer player, String text) {
        if (text == null || text.indexOf('%') < 0) {
            return text;
        }
        if (papi) {
            return PapiBridge.apply(player, text);
        }
        return applyOwn(player, text);
    }

    /**
     * Текст администратора для отображения игроку: PlaceholderAPI + встроенные
     * {online} (без невидимых для него), {max}, {tps}, {ping}, {player}, {name},
     * {world}, {time}, {balance} + цвета.
     */
    public String fill(Player player, String text) {
        if (text.contains("{balance}")) {
            text = text.replace("{balance}", plugin.meta().balance(player));
        }
        if (text.contains("{online}")) {
            text = text.replace("{online}",
                    String.valueOf(Bukkit.getOnlinePlayers().stream().filter(player::canSee).count()));
        }
        text = apply(player, text)
                .replace("{max}", String.valueOf(Bukkit.getMaxPlayers()))
                .replace("{tps}", String.format(Locale.ROOT, "%.1f", Math.min(20.0, Bukkit.getTPS()[0])))
                .replace("{ping}", String.valueOf(player.spigot().getPing()))
                .replace("{player}", player.getName())
                .replace("{name}", player.getDisplayName())
                .replace("{world}", player.getWorld().getName())
                .replace("{time}", new SimpleDateFormat("HH:mm").format(new Date()));
        return Messages.color(text);
    }

    private String applyOwn(OfflinePlayer player, String text) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int start = text.indexOf("%vladcore_", i);
            int end = start < 0 ? -1 : text.indexOf('%', start + 1);
            if (start < 0 || end < 0) {
                out.append(text, i, text.length());
                break;
            }
            String value = resolve(player, text.substring(start + "%vladcore_".length(), end));
            out.append(text, i, start).append(value != null ? value : text.substring(start, end + 1));
            i = end + 1;
        }
        return out.toString();
    }
}
