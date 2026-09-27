package dev.vlad.core.modules.teleport;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.module.Module;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/** /tpa, /tpahere, /tpaccept, /tpdeny, /back, /spawn, /setspawn. */
public final class TeleportModule extends Module implements Listener {

    private final TpaRequests requests = new TpaRequests();
    private final File spawnFile;
    private Location spawn;
    private boolean backOnDeath;

    public TeleportModule(VladCore plugin) {
        super(plugin, "teleport");
        this.spawnFile = new File(plugin.getDataFolder(), "spawn.yml");
    }

    @Override
    protected void onEnable() {
        loadSettings();
        loadSpawn();
        listen(this);
        command(new TpaCommand("tpa", "vcore.tpa", false));
        command(new TpaCommand("tpahere", "vcore.tpahere", true));
        command(new AnswerCommand("tpaccept", "vcore.tpa", true, "tpyes"));
        command(new AnswerCommand("tpdeny", "vcore.tpa", false, "tpno"));
        command(new BackCommand());
        command(new SpawnCommand());
        command(new SetSpawnCommand());
    }

    @Override
    protected void onReload() {
        loadSettings();
    }

    private void loadSettings() {
        requests.setTimeout(plugin.getConfig().getInt("teleport.request-timeout", 60));
        backOnDeath = plugin.getConfig().getBoolean("teleport.back-on-death", true);
    }

    private void loadSpawn() {
        spawn = YamlConfiguration.loadConfiguration(spawnFile).getLocation("spawn");
    }

    private void saveSpawn() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("spawn", spawn);
        try {
            yaml.save(spawnFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить spawn.yml", e);
        }
    }

    private Location spawnLocation() {
        return spawn != null ? spawn : Bukkit.getWorlds().get(0).getSpawnLocation();
    }

    private void setBack(Player player, Location location) {
        plugin.players().get(player.getUniqueId()).set("teleport.back", location);
    }

    // ---------------- слушатели ----------------

    /** Запоминаем точку для /back перед любым телепортом командой или плагином. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        TeleportCause cause = event.getCause();
        if (cause == TeleportCause.COMMAND || cause == TeleportCause.PLUGIN) {
            setBack(event.getPlayer(), event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        if (backOnDeath) {
            setBack(event.getEntity(), event.getEntity().getLocation());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        requests.forget(event.getPlayer().getUniqueId());
    }

    // ---------------- команды ----------------

    private final class TpaCommand extends ModuleCommand {
        private final boolean here;

        TpaCommand(String name, String permission, boolean here) {
            super(TeleportModule.this.plugin, name, permission, "/" + name + " <игрок>");
            this.here = here;
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            Player target = visible(sender, args[0]);
            if (target == null) {
                plugin.messages().send(sender, "player-not-found", "player", args[0]);
                return;
            }
            if (target == player) {
                plugin.messages().send(sender, "teleport.self");
                return;
            }
            requests.add(player.getUniqueId(), target.getUniqueId(), here);
            String timeout = String.valueOf(requests.timeout());
            plugin.messages().send(player, "teleport.request-sent", "player", target.getName(), "seconds", timeout);
            plugin.messages().send(target, here ? "teleport.request-here" : "teleport.request",
                    "player", player.getName(), "seconds", timeout);
            target.spigot().sendMessage(buttons(player.getName()));
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 ? onlineNames(sender, args[0]) : Collections.emptyList();
        }
    }

    private BaseComponent[] buttons(String requester) {
        ComponentBuilder builder = new ComponentBuilder("");
        builder.append(TextComponent.fromLegacyText(plugin.messages().get("teleport.button-accept")))
                .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpaccept " + requester));
        builder.append(" ").reset();
        builder.append(TextComponent.fromLegacyText(plugin.messages().get("teleport.button-deny")))
                .event(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tpdeny " + requester));
        return builder.create();
    }

    private final class AnswerCommand extends ModuleCommand {
        private final boolean accept;

        AnswerCommand(String name, String permission, boolean accept, String alias) {
            super(TeleportModule.this.plugin, name, permission, "/" + name + " [игрок]", alias);
            this.accept = accept;
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            UUID from = null;
            if (args.length > 0) {
                Player named = Bukkit.getPlayerExact(args[0]);
                if (named == null) {
                    plugin.messages().send(sender, "player-not-found", "player", args[0]);
                    return;
                }
                from = named.getUniqueId();
            }
            TpaRequests.Request request = requests.take(player.getUniqueId(), from);
            Player requester = request == null ? null : Bukkit.getPlayer(request.from);
            if (requester == null) {
                plugin.messages().send(player, "teleport.no-request");
                return;
            }
            if (!accept) {
                plugin.messages().send(player, "teleport.denied", "player", requester.getName());
                plugin.messages().send(requester, "teleport.denied-by", "player", player.getName());
                return;
            }
            plugin.messages().send(player, "teleport.accepted", "player", requester.getName());
            plugin.messages().send(requester, "teleport.accepted-by", "player", player.getName());
            // Кто движется: при /tpa — отправитель к получателю, при /tpahere — наоборот.
            Player mover = request.here ? player : requester;
            Player anchor = request.here ? requester : player;
            UUID anchorId = anchor.getUniqueId();
            plugin.teleporter().teleport(mover, () -> {
                Player a = Bukkit.getPlayer(anchorId);
                return a == null ? null : a.getLocation();
            });
        }
    }

    private final class BackCommand extends ModuleCommand {
        BackCommand() {
            super(TeleportModule.this.plugin, "back", "vcore.back", "/back");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            Location back = plugin.players().get(player.getUniqueId()).getLocation("teleport.back");
            if (back == null) {
                plugin.messages().send(player, "teleport.no-back");
                return;
            }
            plugin.teleporter().teleport(player, () -> back);
        }
    }

    private final class SpawnCommand extends ModuleCommand {
        SpawnCommand() {
            super(TeleportModule.this.plugin, "spawn", "vcore.spawn", "/spawn [игрок]");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player target = target(sender, args, 0);
            if (target == null) {
                return;
            }
            if (target == sender) {
                plugin.teleporter().teleport(target, TeleportModule.this::spawnLocation);
            } else {
                // Админ отправляет другого — без задержки.
                target.teleportAsync(spawnLocation());
                plugin.messages().send(sender, "teleport.spawn-other", "player", target.getName());
            }
        }
    }

    private final class SetSpawnCommand extends ModuleCommand {
        SetSpawnCommand() {
            super(TeleportModule.this.plugin, "setspawn", "vcore.setspawn", "/setspawn");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            spawn = player.getLocation();
            saveSpawn();
            plugin.messages().send(player, "teleport.spawn-set");
        }
    }
}
