package dev.vlad.core.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Дописывает в конфиг на сервере ключи, появившиеся в новой версии плагина.
 * Файл пересобирается из шаблона в jar (с его комментариями), а в каждую
 * строку-значение подставляется значение администратора, если оно было задано.
 * Перед перезаписью старый файл копируется в &lt;имя&gt;.bak.
 *
 * Ограничение: подставляются только однострочные значения (числа, строки, true/false);
 * списки берутся из шаблона. Ключи, которых нет в шаблоне, не переносятся.
 */
public final class ConfigUpdater {

    private static final Pattern KEY_LINE = Pattern.compile("^( *)([^#\\s][^:]*):(.*)$");

    private ConfigUpdater() {
    }

    public static void update(JavaPlugin plugin, String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) {
            plugin.saveResource(name, false);
            return;
        }
        List<String> template;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(plugin.getResource(name), StandardCharsets.UTF_8))) {
            template = reader.lines().collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        YamlConfiguration current = YamlConfiguration.loadConfiguration(file);
        YamlConfiguration defaults = new YamlConfiguration();
        try {
            defaults.loadFromString(String.join("\n", template));
        } catch (Exception e) {
            throw new IllegalStateException("Сломан шаблон " + name + " в jar", e);
        }
        boolean missing = defaults.getKeys(true).stream().anyMatch(key -> !current.contains(key, true));
        if (!missing) {
            return;
        }

        List<String> out = new ArrayList<>(template.size());
        Deque<String[]> path = new ArrayDeque<>(); // {отступ, ключ}
        for (String line : template) {
            Matcher m = KEY_LINE.matcher(line);
            if (!m.matches()) {
                out.add(line);
                continue;
            }
            int indent = m.group(1).length();
            while (!path.isEmpty() && Integer.parseInt(path.peek()[0]) >= indent) {
                path.pop();
            }
            String key = m.group(2).trim();
            String bareKey = key.replaceAll("^[\"']|[\"']$", ""); // "3" → 3 для пути
            String rest = m.group(3).trim();
            if (rest.isEmpty() || rest.startsWith("#")) {
                path.push(new String[]{String.valueOf(indent), bareKey});
                out.add(line);
                continue;
            }
            StringBuilder full = new StringBuilder();
            path.descendingIterator().forEachRemaining(p -> full.append(p[1]).append('.'));
            full.append(bareKey);
            String value = current.contains(full.toString(), true) ? scalar(current.get(full.toString())) : null;
            out.add(value == null ? line : m.group(1) + key + ": " + value);
        }

        try {
            Files.copy(file.toPath(), new File(plugin.getDataFolder(), name + ".bak").toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.write(file.toPath(), out, StandardCharsets.UTF_8);
            plugin.getLogger().info(name + ": добавлены новые настройки (старая версия в " + name + ".bak)");
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось обновить " + name + ": " + e.getMessage());
        }
    }

    /** Значение в YAML-записи одной строкой, либо null, если оно многострочное. */
    private static String scalar(Object value) {
        YamlConfiguration tmp = new YamlConfiguration();
        tmp.set("v", value);
        String dumped = tmp.saveToString().trim();
        if (!dumped.startsWith("v: ") || dumped.contains("\n")) {
            return null;
        }
        return dumped.substring(3);
    }
}
