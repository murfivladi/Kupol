package dev.vlad.core.util;

import dev.vlad.core.VladCore;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;

import java.awt.Color;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Сообщения из messages.yml с цветами через & и плейсхолдерами {name}. */
public final class Messages {

    private final VladCore plugin;
    private final File file;
    private YamlConfiguration config;
    private YamlConfiguration defaults;

    public Messages(VladCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "messages.yml");
        reload();
    }

    public void reload() {
        config = YamlConfiguration.loadConfiguration(file);
        // Ключи, которых нет в файле на сервере (добавлены в новой версии), берём из jar.
        defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(plugin.getResource("messages.yml"), StandardCharsets.UTF_8));
    }

    /** @param placeholders пары ключ, значение: "player", name, ... */
    public String get(String key, String... placeholders) {
        String text = config.getString(key, defaults.getString(key, key));
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return color(text);
    }

    public void send(CommandSender to, String key, String... placeholders) {
        to.sendMessage(get("prefix") + get(key, placeholders));
    }

    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    /** &lt;gradient:#5B8CFF:#C438FF&gt;текст&lt;/gradient&gt; — как в MiniMessage. */
    private static final Pattern GRADIENT = Pattern.compile("(?i)<gradient((?::#[0-9a-f]{6})+)>(.*?)</gradient>");
    private static final Pattern FORMAT_CODES = Pattern.compile("(?i)[&§][k-o]");

    /** &-коды, HEX-цвета вида &#ff8800 и градиенты &lt;gradient:#a:#b&gt;...&lt;/gradient&gt;. */
    public static String color(String text) {
        if (text.indexOf('<') >= 0) {
            text = gradients(text);
        }
        Matcher m = HEX.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, net.md_5.bungee.api.ChatColor.of("#" + m.group(1)).toString());
        }
        m.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    private static String gradients(String text) {
        Matcher m = GRADIENT.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            List<Color> stops = new ArrayList<>();
            for (String hex : m.group(1).substring(1).split(":")) {
                stops.add(Color.decode(hex));
            }
            // Оформление (&l и т.п.) внутри градиента повторяем после каждой буквы.
            String inner = m.group(2);
            StringBuilder formats = new StringBuilder();
            Matcher f = FORMAT_CODES.matcher(inner);
            while (f.find()) {
                formats.append('§').append(Character.toLowerCase(f.group().charAt(1)));
            }
            String plain = FORMAT_CODES.matcher(inner).replaceAll("");
            m.appendReplacement(sb, Matcher.quoteReplacement(Gradient.apply(plain, stops, formats.toString())));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
