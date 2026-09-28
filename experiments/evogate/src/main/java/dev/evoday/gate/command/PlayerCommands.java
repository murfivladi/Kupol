package dev.evoday.gate.command;

import dev.evoday.gate.EvoGate;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

// register, login, changepassword, logout
public final class PlayerCommands implements TabExecutor {

    private final EvoGate plugin;

    public PlayerCommands(EvoGate plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only for players");
            return true;
        }
        switch (command.getName()) {
            case "register" -> {
                if (args.length != 2) {
                    usage(player, "/register <пароль> <пароль>");
                } else {
                    plugin.auth().register(player, args[0], args[1]);
                }
            }
            case "login" -> {
                if (args.length != 1) {
                    usage(player, "/login <пароль>");
                } else {
                    plugin.auth().login(player, args[0]);
                }
            }
            case "changepassword" -> {
                if (args.length != 2) {
                    usage(player, "/changepassword <старый> <новый>");
                } else {
                    plugin.auth().changePassword(player, args[0], args[1]);
                }
            }
            case "logout" -> plugin.auth().logout(player);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void usage(Player player, String usage) {
        plugin.messages().send(player, "usage", "usage", usage);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }
}
