package dev.vlad.core.command;

import dev.vlad.core.VladCore;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** База для команд модулей: права, проверки и автодополнение ников. */
public abstract class ModuleCommand extends Command {

    protected final VladCore plugin;

    protected ModuleCommand(VladCore plugin, String name, String permission, String usage, String... aliases) {
        super(name, "", usage, Arrays.asList(aliases));
        this.plugin = plugin;
        setPermission(permission);
    }

    protected abstract void run(CommandSender sender, String[] args);

    @Override
    public final boolean execute(CommandSender sender, String label, String[] args) {
        if (getPermission() != null && !sender.hasPermission(getPermission())) {
            plugin.messages().send(sender, "no-permission");
            return true;
        }
        run(sender, args);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission(getPermission() + ".others")) {
            return onlineNames(sender, args[0]);
        }
        return Collections.emptyList();
    }

    /**
     * Цель команды: сам игрок, либо другой из args[index] (нужно право .others).
     * Возвращает null и сам пишет ошибку, если цель не найдена.
     */
    protected Player target(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            if (!sender.hasPermission(getPermission() + ".others")) {
                plugin.messages().send(sender, "no-permission");
                return null;
            }
            Player player = visible(sender, args[index]);
            if (player == null) {
                plugin.messages().send(sender, "player-not-found", "player", args[index]);
            }
            return player;
        }
        if (sender instanceof Player) {
            return (Player) sender;
        }
        plugin.messages().send(sender, "usage", "usage", getUsage());
        return null;
    }

    /** Онлайн-игрок по нику, если отправитель его видит (игроки в ванише "не существуют"). */
    protected static Player visible(CommandSender sender, String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null || (sender instanceof Player && !((Player) sender).canSee(player))) {
            return null;
        }
        return player;
    }

    /** Отправитель как игрок, либо null с сообщением "только для игроков". */
    protected Player player(CommandSender sender) {
        if (sender instanceof Player) {
            return (Player) sender;
        }
        plugin.messages().send(sender, "players-only");
        return null;
    }

    /** Ники онлайн-игроков, которых видит viewer (невидимые в ванише не подсказываются). */
    protected static List<String> onlineNames(CommandSender viewer, String prefix) {
        String lower = prefix.toLowerCase();
        Player viewerPlayer = viewer instanceof Player ? (Player) viewer : null;
        return Bukkit.getOnlinePlayers().stream()
                .filter(p -> viewerPlayer == null || viewerPlayer.canSee(p))
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(lower))
                .collect(Collectors.toList());
    }
}
