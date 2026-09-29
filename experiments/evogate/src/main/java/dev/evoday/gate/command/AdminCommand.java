package dev.evoday.gate.command;

import dev.evoday.gate.EvoGate;
import dev.evoday.gate.storage.AccountRepo;
import dev.evoday.gate.util.Passwords;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public final class AdminCommand implements TabExecutor {

    private static final List<String> SUB = List.of("unregister", "forcelogin", "changepass", "reload");

    private final EvoGate plugin;

    public AdminCommand(EvoGate plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                plugin.reloadConfig();
                plugin.messages().reload();
                plugin.messages().send(sender, "admin-reloaded");
            }
            case "unregister" -> {
                if (args.length != 2) {
                    break;
                }
                String name = args[1];
                plugin.db().async(c -> AccountRepo.delete(c, name)).whenComplete((ok, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (error != null) {
                                plugin.getLogger().log(Level.SEVERE, "unregister failed", error);
                                plugin.messages().send(sender, "error");
                                return;
                            }
                            if (!ok) {
                                plugin.messages().send(sender, "admin-not-found", "name", name);
                                return;
                            }
                            Player online = Bukkit.getPlayerExact(name);
                            if (online != null) {
                                plugin.auth().onUnregistered(online);
                            }
                            plugin.messages().send(sender, "admin-unregistered", "name", name);
                        }));
                return true;
            }
            case "forcelogin" -> {
                if (args.length != 2) {
                    break;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    plugin.messages().send(sender, "admin-offline", "name", args[1]);
                } else {
                    plugin.auth().forceLogin(target);
                    plugin.messages().send(sender, "admin-forcelogin", "name", target.getName());
                }
                return true;
            }
            case "changepass" -> {
                if (args.length != 3) {
                    break;
                }
                String name = args[1];
                String password = args[2];
                plugin.db().async(c -> AccountRepo.setHash(c, name, Passwords.hash(password))).whenComplete((ok, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (error != null) {
                                plugin.getLogger().log(Level.SEVERE, "changepass failed", error);
                                plugin.messages().send(sender, "error");
                            } else if (!ok) {
                                plugin.messages().send(sender, "admin-not-found", "name", name);
                            } else {
                                plugin.messages().send(sender, "admin-password", "name", name);
                            }
                        }));
                return true;
            }
            default -> {
            }
        }
        if (!sub.equals("reload")) {
            plugin.messages().send(sender, "admin-usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1) {
            return SUB.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            return null; // ники онлайн
        }
        return List.of();
    }
}
