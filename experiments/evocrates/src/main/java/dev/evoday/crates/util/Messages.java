package dev.evoday.crates.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

// плейсхолдеры парами: "name", value, ... ; Component вставляется как есть, остальное как текст
public final class Messages {

    public static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private YamlConfiguration file;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        File target = new File(plugin.getDataFolder(), "messages.yml");
        if (!target.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        file = YamlConfiguration.loadConfiguration(target);
        var in = plugin.getResource("messages.yml");
        if (in != null) {
            file.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }

    public Component raw(String key, Object... placeholders) {
        return MM.deserialize(file.getString(key, key), resolver(placeholders));
    }

    public Component get(String key, Object... placeholders) {
        return MM.deserialize(file.getString("prefix", "")).append(raw(key, placeholders));
    }

    public void send(CommandSender to, String key, Object... placeholders) {
        to.sendMessage(get(key, placeholders));
    }

    private static TagResolver resolver(Object... placeholders) {
        TagResolver.Builder builder = TagResolver.builder();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String name = String.valueOf(placeholders[i]);
            Object value = placeholders[i + 1];
            builder.resolver(value instanceof Component c
                    ? Placeholder.component(name, c)
                    : Placeholder.unparsed(name, String.valueOf(value)));
        }
        return builder.build();
    }
}
