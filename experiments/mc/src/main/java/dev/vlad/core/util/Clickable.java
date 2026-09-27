package dev.vlad.core.util;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;

/** Список названий, по клику на которые выполняется команда (например, "/home база"). */
public final class Clickable {

    private Clickable() {
    }

    public static void list(CommandSender to, Collection<String> names, String command, String hover) {
        String itemColor = Messages.color("&f");
        String separator = Messages.color("&7, ");
        if (!(to instanceof Player)) {
            to.sendMessage(itemColor + String.join(separator, names));
            return;
        }
        ComponentBuilder builder = new ComponentBuilder("");
        boolean first = true;
        for (String name : names) {
            if (!first) {
                builder.append(TextComponent.fromLegacyText(separator), ComponentBuilder.FormatRetention.NONE);
            }
            first = false;
            builder.append(TextComponent.fromLegacyText(itemColor + name), ComponentBuilder.FormatRetention.NONE)
                    .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command + " " + name))
                    .event(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hover.replace("{name}", name))));
        }
        ((Player) to).spigot().sendMessage(builder.create());
    }
}
