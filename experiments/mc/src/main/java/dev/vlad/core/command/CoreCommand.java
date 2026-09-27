package dev.vlad.core.command;

import dev.vlad.core.VladCore;
import dev.vlad.core.module.Module;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** /vcore reload | modules */
public final class CoreCommand implements TabExecutor {

    private final VladCore plugin;

    public CoreCommand(VladCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vcore.admin")) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "reload":
                plugin.reload();
                plugin.messages().send(sender, "reloaded");
                break;
            case "modules":
                plugin.messages().send(sender, "modules-header");
                for (Module module : plugin.modules().all()) {
                    String state = plugin.messages().get(module.isEnabled() ? "module-on" : "module-off");
                    sender.sendMessage(plugin.messages().get("module-line", "module", module.id(), "state", state));
                }
                break;
            case "placeholders":
                plugin.messages().send(sender, "placeholders-header",
                        "papi", plugin.messages().get(plugin.placeholders().papiAvailable() ? "module-on" : "module-off"));
                for (String name : plugin.placeholders().names()) {
                    sender.sendMessage(plugin.messages().get("placeholders-line", "name", name));
                }
                break;
            default:
                plugin.messages().send(sender, "usage", "usage", "/" + label + " reload|modules|placeholders");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("vcore.admin")) {
            return Arrays.asList("reload", "modules", "placeholders").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
