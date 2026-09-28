package dev.evoday.crates;

import dev.evoday.crates.crate.Crate;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

public final class CratesCommand implements TabExecutor {

    private static final List<String> ADMIN = List.of("give", "take", "setblock", "removeblock", "additem", "reload");

    private final EvoCrates plugin;

    public CratesCommand(EvoCrates plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "keys" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("keys")) {
            if (sender instanceof Player p) {
                showKeys(p);
            }
            return true;
        }
        if (!sender.hasPermission("evocrates.admin")) {
            if (sender instanceof Player p) {
                showKeys(p);
            }
            return true;
        }
        switch (sub) {
            case "give", "take" -> giveOrTake(sender, args, sub.equals("give"));
            case "setblock" -> setBlock(sender, args);
            case "removeblock" -> removeBlock(sender);
            case "additem" -> addItem(sender, args);
            case "reload" -> {
                plugin.reloadAll();
                plugin.messages().send(sender, "admin-reloaded");
            }
            default -> plugin.messages().send(sender, "admin-usage");
        }
        return true;
    }

    private void showKeys(Player player) {
        Map<String, Integer> keys = plugin.service().cachedKeys(player.getUniqueId());
        boolean any = false;
        plugin.messages().send(player, "keys-header");
        for (Crate crate : plugin.crates().all()) {
            int amount = keys.getOrDefault(crate.id(), 0);
            if (amount > 0) {
                any = true;
                player.sendMessage(plugin.messages().raw("keys-line", "crate", crate.name(), "amount", amount));
            }
        }
        if (!any) {
            plugin.messages().send(player, "keys-none");
        }
    }

    // /crates give <ник> <кейс> [кол-во]
    private void giveOrTake(CommandSender sender, String[] args, boolean give) {
        if (args.length < 3) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        Crate crate = plugin.crates().get(args[2]);
        if (crate == null) {
            plugin.messages().send(sender, "admin-unknown-crate", "id", args[2]);
            return;
        }
        int amount;
        try {
            amount = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        } catch (NumberFormatException e) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        if (amount <= 0) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        int finalAmount = amount;
        plugin.service().addKeys(target.getUniqueId(), crate.id(), give ? amount : -amount).whenComplete((now, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (error != null) {
                        plugin.getLogger().log(Level.SEVERE, "can't change keys", error);
                        plugin.messages().send(sender, "error");
                        return;
                    }
                    plugin.messages().send(sender, give ? "admin-given" : "admin-taken",
                            "amount", finalAmount, "crate", crate.name(), "player", args[1]);
                }));
    }

    private void setBlock(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 2) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        Crate crate = plugin.crates().get(args[1]);
        if (crate == null) {
            plugin.messages().send(sender, "admin-unknown-crate", "id", args[1]);
            return;
        }
        Block block = player.getTargetBlockExact(5);
        if (block == null || block.getType().isAir()) {
            plugin.messages().send(sender, "admin-no-block");
            return;
        }
        plugin.blocks().set(block, crate);
        plugin.messages().send(sender, "admin-block-set", "crate", crate.name());
    }

    private void removeBlock(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return;
        }
        Block block = player.getTargetBlockExact(5);
        if (block == null) {
            plugin.messages().send(sender, "admin-no-block");
        } else if (!plugin.blocks().remove(block)) {
            plugin.messages().send(sender, "admin-not-crate");
        } else {
            plugin.messages().send(sender, "admin-block-removed");
        }
    }

    // /crates additem <кейс> <вес> [редкость]
    private void addItem(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || args.length < 3) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        Crate crate = plugin.crates().get(args[1]);
        if (crate == null) {
            plugin.messages().send(sender, "admin-unknown-crate", "id", args[1]);
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType().isAir()) {
            plugin.messages().send(sender, "admin-empty-hand");
            return;
        }
        int weight;
        try {
            weight = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            plugin.messages().send(sender, "admin-usage");
            return;
        }
        String rarity = args.length > 3 ? args[3].toLowerCase(Locale.ROOT) : "common";
        try {
            String id = plugin.crates().addItem(crate.id(), hand, weight, rarity);
            plugin.reloadAll();
            plugin.messages().send(sender, "admin-item-added", "crate", crate.name(), "id", id);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "can't save crates.yml", e);
            plugin.messages().send(sender, "error");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("evocrates.admin")) {
            return List.of();
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options;
        if (args.length == 1) {
            options = ADMIN;
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("take"))) {
            return null;
        } else if ((args.length == 2 && (args[0].equalsIgnoreCase("setblock") || args[0].equalsIgnoreCase("additem")))
                || (args.length == 3 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("take")))) {
            options = plugin.crates().all().stream().map(Crate::id).toList();
        } else if (args.length == 4 && args[0].equalsIgnoreCase("additem")) {
            var section = plugin.getConfig().getConfigurationSection("rarities");
            options = section == null ? List.of() : List.copyOf(section.getKeys(false));
        } else {
            return List.of();
        }
        return options.stream().filter(o -> o.startsWith(last)).toList();
    }
}
