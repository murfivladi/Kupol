package dev.vlad.core.modules.homes;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.module.Module;
import dev.vlad.core.storage.PlayerData;
import dev.vlad.core.gui.ConfirmMenu;
import dev.vlad.core.gui.Items;
import dev.vlad.core.gui.PagedMenu;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Дома: /sethome, /home, /delhome, /homes.
 * Лимит — наибольшее N из прав vcore.homes.limit.N, иначе homes.default-limit;
 * vcore.homes.unlimited снимает лимит.
 */
public final class HomesModule extends Module {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_\\-а-яА-ЯёЁ]{1,16}");
    private static final String LIMIT_PREFIX = "vcore.homes.limit.";
    private static final String DEFAULT_HOME = "home";

    public HomesModule(VladCore plugin) {
        super(plugin, "homes");
    }

    @Override
    protected void onEnable() {
        placeholder("homes_count", p -> {
            ConfigurationSection section = plugin.players().get(p.getUniqueId()).section("homes");
            return String.valueOf(section == null ? 0 : section.getKeys(false).size());
        });
        placeholder("homes_limit", p -> {
            int limit = p.getPlayer() != null ? limit(p.getPlayer()) : plugin.getConfig().getInt("homes.default-limit", 3);
            return limit == Integer.MAX_VALUE ? "∞" : String.valueOf(limit);
        });
        command(new SetHomeCommand());
        command(new HomeCommand());
        command(new DelHomeCommand());
        command(new HomesCommand());
    }

    private List<String> homes(Player player) {
        ConfigurationSection section = plugin.players().get(player.getUniqueId()).section("homes");
        if (section == null) {
            return new ArrayList<>();
        }
        List<String> names = new ArrayList<>(section.getKeys(false));
        Collections.sort(names);
        return names;
    }

    private int limit(Player player) {
        if (player.hasPermission("vcore.homes.unlimited")) {
            return Integer.MAX_VALUE;
        }
        int limit = plugin.getConfig().getInt("homes.default-limit", 3);
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (info.getValue() && perm.startsWith(LIMIT_PREFIX)) {
                try {
                    limit = Math.max(limit, Integer.parseInt(perm.substring(LIMIT_PREFIX.length())));
                } catch (NumberFormatException ignored) {
                    // кривое право вида vcore.homes.limit.abc — пропускаем
                }
            }
        }
        return limit;
    }

    private List<String> complete(Player player, String prefix) {
        String lower = prefix.toLowerCase();
        return homes(player).stream().filter(h -> h.startsWith(lower)).collect(Collectors.toList());
    }

    private abstract class HomeNameCommand extends ModuleCommand {
        HomeNameCommand(String name, String permission, String usage, String... aliases) {
            super(HomesModule.this.plugin, name, permission, usage, aliases);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length == 1 && sender instanceof Player) {
                return complete((Player) sender, args[0]);
            }
            return Collections.emptyList();
        }
    }

    private final class SetHomeCommand extends ModuleCommand {
        SetHomeCommand() {
            super(HomesModule.this.plugin, "sethome", "vcore.home", "/sethome [название]", "createhome");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            String name = args.length > 0 ? args[0].toLowerCase() : DEFAULT_HOME;
            if (!NAME.matcher(name).matches()) {
                plugin.messages().send(player, "homes.bad-name");
                return;
            }
            List<String> homes = homes(player);
            int limit = limit(player);
            if (!homes.contains(name) && homes.size() >= limit) {
                plugin.messages().send(player, "homes.limit", "limit", String.valueOf(limit));
                return;
            }
            plugin.players().get(player.getUniqueId()).set("homes." + name, player.getLocation());
            plugin.messages().send(player, "homes.set", "home", name);
        }
    }

    private final class HomeCommand extends HomeNameCommand {
        HomeCommand() {
            super("home", "vcore.home", "/home [название]", "h");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            List<String> homes = homes(player);
            String name;
            if (args.length > 0) {
                name = args[0].toLowerCase();
            } else if (homes.contains(DEFAULT_HOME)) {
                name = DEFAULT_HOME;
            } else if (homes.size() == 1) {
                name = homes.get(0);
            } else {
                showList(player, homes);
                return;
            }
            Location location = plugin.players().get(player.getUniqueId()).getLocation("homes." + name);
            if (location == null) {
                plugin.messages().send(player, "homes.not-found", "home", name);
                return;
            }
            plugin.teleporter().teleport(player, () -> location);
        }
    }

    private final class DelHomeCommand extends HomeNameCommand {
        DelHomeCommand() {
            super("delhome", "vcore.home", "/delhome <название>", "removehome");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            if (args.length == 0) {
                plugin.messages().send(player, "usage", "usage", getUsage());
                return;
            }
            String name = args[0].toLowerCase();
            PlayerData data = plugin.players().get(player.getUniqueId());
            if (data.getLocation("homes." + name) == null) {
                plugin.messages().send(player, "homes.not-found", "home", name);
                return;
            }
            data.set("homes." + name, null);
            plugin.messages().send(player, "homes.deleted", "home", name);
        }
    }

    private final class HomesCommand extends ModuleCommand {
        HomesCommand() {
            super(HomesModule.this.plugin, "homes", "vcore.home", "/homes");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player != null) {
                showList(player, homes(player));
            }
        }
    }

    /** Для игрока — меню домов. */
    private void showList(Player player, List<String> homes) {
        new HomesMenu(player).open();
    }

    private final class HomesMenu extends PagedMenu<String> {
        HomesMenu(Player viewer) {
            super(viewer, plugin.messages().get("homes.menu-title"));
        }

        @Override
        protected List<String> entries() {
            return homes(viewer);
        }

        @Override
        protected ItemStack icon(String home, int index) {
            Location l = plugin.players().get(viewer.getUniqueId()).getLocation("homes." + home);
            String world = l == null || l.getWorld() == null ? "?" : l.getWorld().getName();
            return Items.of(Material.RED_BED, plugin.messages().get("homes.menu-item", "home", home),
                    lines("homes.menu-lore", "world", world,
                            "x", l == null ? "?" : String.valueOf(l.getBlockX()),
                            "y", l == null ? "?" : String.valueOf(l.getBlockY()),
                            "z", l == null ? "?" : String.valueOf(l.getBlockZ())));
        }

        @Override
        protected void onClick(String home, InventoryClickEvent event) {
            if (event.getClick() == ClickType.SHIFT_RIGHT) {
                new ConfirmMenu(viewer, plugin.messages().get("homes.menu-confirm", "home", home),
                        icon(home, 0),
                        () -> {
                            viewer.performCommand("delhome " + home);
                            open();
                        },
                        this::open).open();
                return;
            }
            viewer.closeInventory();
            viewer.performCommand("home " + home);
        }

        @Override
        protected ItemStack emptyIcon() {
            return Items.of(Material.BARRIER, plugin.messages().get("homes.menu-empty"), Collections.emptyList());
        }

        @Override
        protected void bottomRow() {
            List<String> homes = homes(viewer);
            int limit = limit(viewer);
            String max = limit == Integer.MAX_VALUE ? "∞" : String.valueOf(limit);
            set(47, Items.of(Material.LIME_BED, plugin.messages().get("homes.menu-add"),
                    lines("homes.menu-add-lore", "count", String.valueOf(homes.size()), "limit", max)), e -> {
                // Свободное имя: home, home2, home3...
                String name = DEFAULT_HOME;
                for (int i = 2; homes.contains(name); i++) {
                    name = DEFAULT_HOME + i;
                }
                viewer.performCommand("sethome " + name);
                refresh();
            });
            set(51, Items.of(Material.OAK_DOOR, plugin.messages().get("gui.close"), Collections.emptyList()),
                    e -> viewer.closeInventory());
        }
    }

    /** Многострочное описание из messages.yml (строки через \n). */
    private List<String> lines(String key, String... placeholders) {
        return Arrays.asList(plugin.messages().get(key, placeholders).split("\n"));
    }
}
