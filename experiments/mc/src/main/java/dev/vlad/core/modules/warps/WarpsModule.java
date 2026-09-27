package dev.vlad.core.modules.warps;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.gui.Items;
import dev.vlad.core.gui.PagedMenu;
import dev.vlad.core.module.Module;
import dev.vlad.core.util.Clickable;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Варпы: /warp, /warps, /setwarp, /delwarp. Хранятся в warps.yml.
 * При warps.per-warp-permission: true для варпа X нужно право vcore.warp.X.
 */
public final class WarpsModule extends Module {

    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_\\-а-яА-ЯёЁ]{1,24}");

    private final File file;
    private final Map<String, Location> warps = new TreeMap<>();

    public WarpsModule(VladCore plugin) {
        super(plugin, "warps");
        this.file = new File(plugin.getDataFolder(), "warps.yml");
    }

    @Override
    protected void onEnable() {
        load();
        command(new WarpCommand());
        command(new WarpsCommand());
        command(new SetWarpCommand());
        command(new DelWarpCommand());
    }

    private void load() {
        warps.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String name : yaml.getKeys(false)) {
            Location location = yaml.getLocation(name);
            if (location != null) {
                warps.put(name, location);
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        warps.forEach(yaml::set);
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить warps.yml", e);
        }
    }

    private boolean canUse(CommandSender sender, String warp) {
        return !plugin.getConfig().getBoolean("warps.per-warp-permission", false)
                || sender.hasPermission("vcore.warp." + warp);
    }

    private List<String> available(CommandSender sender) {
        return warps.keySet().stream().filter(w -> canUse(sender, w)).collect(Collectors.toList());
    }

    private List<String> complete(CommandSender sender, String prefix) {
        String lower = prefix.toLowerCase();
        return available(sender).stream().filter(w -> w.startsWith(lower)).collect(Collectors.toList());
    }

    private final class WarpCommand extends ModuleCommand {
        WarpCommand() {
            super(WarpsModule.this.plugin, "warp", "vcore.warp", "/warp <название> [игрок]");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                showList(sender);
                return;
            }
            String name = args[0].toLowerCase();
            Location location = warps.get(name);
            if (location == null || !canUse(sender, name)) {
                plugin.messages().send(sender, "warps.not-found", "warp", name);
                return;
            }
            Player target = target(sender, args, 1);
            if (target == null) {
                return;
            }
            if (target == sender) {
                plugin.teleporter().teleport(target, () -> location);
            } else {
                target.teleportAsync(location);
                plugin.messages().send(sender, "warps.sent", "player", target.getName(), "warp", name);
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length == 1) {
                return complete(sender, args[0]);
            }
            if (args.length == 2 && sender.hasPermission(getPermission() + ".others")) {
                return onlineNames(sender, args[1]);
            }
            return Collections.emptyList();
        }
    }

    private final class WarpsCommand extends ModuleCommand {
        WarpsCommand() {
            super(WarpsModule.this.plugin, "warps", "vcore.warp", "/warps");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            showList(sender);
        }
    }

    private void showList(CommandSender sender) {
        if (sender instanceof Player) {
            new WarpsMenu((Player) sender).open();
            return;
        }
        List<String> names = available(sender);
        if (names.isEmpty()) {
            plugin.messages().send(sender, "warps.none");
            return;
        }
        plugin.messages().send(sender, "warps.list", "count", String.valueOf(names.size()));
        Clickable.list(sender, names, "/warp", plugin.messages().get("warps.hover"));
    }

    private final class SetWarpCommand extends ModuleCommand {
        SetWarpCommand() {
            super(WarpsModule.this.plugin, "setwarp", "vcore.setwarp", "/setwarp <название>");
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
            String name = args[0].toLowerCase();
            if (!NAME.matcher(name).matches()) {
                plugin.messages().send(sender, "warps.bad-name");
                return;
            }
            warps.put(name, player.getLocation());
            save();
            plugin.messages().send(sender, "warps.set", "warp", name);
        }
    }

    private final class DelWarpCommand extends ModuleCommand {
        DelWarpCommand() {
            super(WarpsModule.this.plugin, "delwarp", "vcore.delwarp", "/delwarp <название>");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            String name = args[0].toLowerCase();
            if (warps.remove(name) == null) {
                plugin.messages().send(sender, "warps.not-found", "warp", name);
                return;
            }
            save();
            plugin.messages().send(sender, "warps.deleted", "warp", name);
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return args.length == 1 ? complete(sender, args[0]) : Collections.emptyList();
        }
    }

    @Override
    protected void onReload() {
        load();
    }

    /** Для будущих модулей (GUI-меню варпов и т.п.). */
    public Map<String, Location> all() {
        return Collections.unmodifiableMap(warps);
    }

    private final class WarpsMenu extends PagedMenu<String> {
        WarpsMenu(Player viewer) {
            super(viewer, plugin.messages().get("warps.menu-title"));
        }

        @Override
        protected List<String> entries() {
            return available(viewer);
        }

        @Override
        protected ItemStack icon(String warp, int index) {
            Location l = warps.get(warp);
            String world = l == null || l.getWorld() == null ? "?" : l.getWorld().getName();
            return Items.of(Material.ENDER_PEARL, plugin.messages().get("warps.menu-item", "warp", warp),
                    Arrays.asList(plugin.messages().get("warps.menu-lore", "world", world).split("\n")));
        }

        @Override
        protected void onClick(String warp, InventoryClickEvent event) {
            viewer.closeInventory();
            viewer.performCommand("warp " + warp);
        }

        @Override
        protected ItemStack emptyIcon() {
            return Items.of(Material.BARRIER, plugin.messages().get("warps.none"), Collections.emptyList());
        }
    }
}
