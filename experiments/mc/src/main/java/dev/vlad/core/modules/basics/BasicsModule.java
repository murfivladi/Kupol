package dev.vlad.core.modules.basics;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.module.Module;
import org.bukkit.GameMode;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** Базовые команды: /heal, /feed, /fly, /gm. */
public final class BasicsModule extends Module {

    public BasicsModule(VladCore plugin) {
        super(plugin, "basics");
    }

    @Override
    protected void onEnable() {
        command(new HealCommand());
        command(new FeedCommand());
        command(new FlyCommand());
        command(new GamemodeCommand());
    }

    /** Сообщение самому игроку, а если цель — другой, то ещё и отправителю. */
    private void report(CommandSender sender, Player target, String selfKey, String otherKey, String... placeholders) {
        plugin.messages().send(target, selfKey, placeholders);
        if (sender != target) {
            String[] withPlayer = Arrays.copyOf(placeholders, placeholders.length + 2);
            withPlayer[placeholders.length] = "player";
            withPlayer[placeholders.length + 1] = target.getName();
            plugin.messages().send(sender, otherKey, withPlayer);
        }
    }

    private final class HealCommand extends ModuleCommand {
        HealCommand() {
            super(BasicsModule.this.plugin,"heal", "vcore.heal", "/heal [игрок]");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player target = target(sender, args, 0);
            if (target == null) {
                return;
            }
            target.setHealth(target.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue());
            target.setFoodLevel(20);
            target.setSaturation(20f);
            target.setFireTicks(0);
            target.getActivePotionEffects().forEach(effect -> target.removePotionEffect(effect.getType()));
            report(sender, target, "basics.healed", "basics.healed-other");
        }
    }

    private final class FeedCommand extends ModuleCommand {
        FeedCommand() {
            super(BasicsModule.this.plugin,"feed", "vcore.feed", "/feed [игрок]");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player target = target(sender, args, 0);
            if (target == null) {
                return;
            }
            target.setFoodLevel(20);
            target.setSaturation(20f);
            report(sender, target, "basics.fed", "basics.fed-other");
        }
    }

    private final class FlyCommand extends ModuleCommand {
        FlyCommand() {
            super(BasicsModule.this.plugin,"fly", "vcore.fly", "/fly [игрок]");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player target = target(sender, args, 0);
            if (target == null) {
                return;
            }
            boolean fly = !target.getAllowFlight();
            target.setAllowFlight(fly);
            if (!fly) {
                target.setFlying(false);
            }
            plugin.messages().send(target, fly ? "basics.fly-on" : "basics.fly-off");
            if (sender != target) {
                String state = plugin.messages().get(fly ? "module-on" : "module-off");
                plugin.messages().send(sender, "basics.fly-other", "player", target.getName(), "state", state);
            }
        }
    }

    private final class GamemodeCommand extends ModuleCommand {
        private final List<String> modes = Arrays.asList("survival", "creative", "adventure", "spectator");

        GamemodeCommand() {
            super(BasicsModule.this.plugin,"gm", "vcore.gamemode", "/gm <режим> [игрок]", "gamemode");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            GameMode mode = parse(args[0]);
            if (mode == null) {
                plugin.messages().send(sender, "basics.bad-gamemode");
                return;
            }
            Player target = target(sender, args, 1);
            if (target == null) {
                return;
            }
            target.setGameMode(mode);
            String name = mode.name().toLowerCase();
            report(sender, target, "basics.gamemode", "basics.gamemode-other", "mode", name);
        }

        private GameMode parse(String arg) {
            switch (arg.toLowerCase()) {
                case "0": case "s": case "survival": return GameMode.SURVIVAL;
                case "1": case "c": case "creative": return GameMode.CREATIVE;
                case "2": case "a": case "adventure": return GameMode.ADVENTURE;
                case "3": case "sp": case "spectator": return GameMode.SPECTATOR;
                default: return null;
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length == 1) {
                return modes.stream().filter(m -> m.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
            }
            if (args.length == 2 && sender.hasPermission(getPermission() + ".others")) {
                return onlineNames(sender, args[1]);
            }
            return super.tabComplete(sender, alias, new String[0]);
        }
    }
}
