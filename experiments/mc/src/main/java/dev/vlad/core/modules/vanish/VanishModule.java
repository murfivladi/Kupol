package dev.vlad.core.modules.vanish;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.module.Module;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.TranslatableComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

/**
 * /vanish: игрок невидим для всех без права vcore.vanish.see, не подбирает предметы,
 * мобы его не замечают, вход/выход без сообщений. Состояние переживает перезаход.
 */
public final class VanishModule extends Module implements Listener {

    private static final String SEE = "vcore.vanish.see";
    private static final String PATH = "vanish.enabled";

    private BukkitTask actionBar;

    public VanishModule(VladCore plugin) {
        super(plugin, "vanish");
    }

    @Override
    protected void onEnable() {
        listen(this);
        placeholder("vanished", p -> yesNo(p.getPlayer() != null && isVanished(p.getPlayer())));
        // Онлайн без невидимых — для табов и скорбордов других плагинов.
        placeholder("online", p -> String.valueOf(Bukkit.getOnlinePlayers().stream().filter(o -> !isVanished(o)).count()));
        command(new VanishCommand());
        Bukkit.getOnlinePlayers().forEach(p -> {
            if (isVanished(p)) {
                apply(p, true);
            }
        });
        actionBar = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            String text = plugin.messages().get("vanish.action-bar");
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (isVanished(p)) {
                    p.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
                }
            }
        }, 40L, 40L);
    }

    @Override
    protected void onDisable() {
        if (actionBar != null) {
            actionBar.cancel();
        }
        // Модуль выключается — показываем всех, иначе они останутся невидимыми до перезахода.
        Bukkit.getOnlinePlayers().forEach(p -> {
            if (isVanished(p)) {
                apply(p, false);
            }
        });
    }

    public boolean isVanished(Player player) {
        return "true".equals(plugin.players().get(player.getUniqueId()).getString(PATH));
    }

    private void apply(Player player, boolean vanish) {
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other == player) {
                continue;
            }
            if (vanish && !other.hasPermission(SEE)) {
                other.hidePlayer(plugin, player);
            } else {
                other.showPlayer(plugin, player);
            }
        }
        player.setCollidable(!vanish);
        player.setSleepingIgnored(vanish);
    }

    private void setVanished(Player player, boolean vanish) {
        plugin.players().get(player.getUniqueId()).set(PATH, vanish ? "true" : null);
        apply(player, vanish);
        if (plugin.getConfig().getBoolean("vanish.fake-messages", true)) {
            fakeJoinQuit(player, vanish);
        }
    }

    /** Для тех, кто не видит ванишнутых, — стандартное "игрок вышел/зашёл". */
    private void fakeJoinQuit(Player player, boolean vanish) {
        TranslatableComponent message = new TranslatableComponent(
                vanish ? "multiplayer.player.left" : "multiplayer.player.joined", player.getDisplayName());
        message.setColor(ChatColor.YELLOW);
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other != player && !other.hasPermission(SEE)) {
                other.spigot().sendMessage(message);
            }
        }
    }

    // ---------------- события ----------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        // Зашедший не видит тех, кто уже в ванише.
        if (!joined.hasPermission(SEE)) {
            for (Player other : Bukkit.getOnlinePlayers()) {
                if (other != joined && isVanished(other)) {
                    joined.hidePlayer(plugin, other);
                }
            }
        }
        if (isVanished(joined)) {
            event.setJoinMessage(null);
            apply(joined, true);
            plugin.messages().send(joined, "vanish.still-vanished");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        if (isVanished(event.getPlayer())) {
            event.setQuitMessage(null);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player && isVanished((Player) event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player && isVanished((Player) event.getTarget())) {
            event.setCancelled(true);
        }
    }

    // ---------------- команда ----------------

    private final class VanishCommand extends ModuleCommand {
        VanishCommand() {
            super(VanishModule.this.plugin, "vanish", "vcore.vanish", "/vanish [игрок]", "v");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player target = target(sender, args, 0);
            if (target == null) {
                return;
            }
            boolean vanish = !isVanished(target);
            setVanished(target, vanish);
            plugin.messages().send(target, vanish ? "vanish.on" : "vanish.off");
            if (sender != target) {
                plugin.messages().send(sender, vanish ? "vanish.on-other" : "vanish.off-other", "player", target.getName());
            }
        }
    }
}
