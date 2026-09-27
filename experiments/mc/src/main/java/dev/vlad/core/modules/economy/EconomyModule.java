package dev.vlad.core.modules.economy;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.gui.Items;
import dev.vlad.core.gui.PagedMenu;
import dev.vlad.core.module.Module;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Экономика: /balance, /pay, /baltop, /eco + провайдер Vault. */
public final class EconomyModule extends Module implements Listener {

    private static final long AUTOSAVE_TICKS = 20L * 60 * 5;
    private static final int TOP_PAGE_SIZE = 10;

    private final Bank bank;
    private BukkitTask autosave;
    private Object vaultProvider;

    public EconomyModule(VladCore plugin) {
        super(plugin, "economy");
        this.bank = new Bank(plugin);
    }

    @Override
    protected void onEnable() {
        bank.load();
        autosave = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, bank::save, AUTOSAVE_TICKS, AUTOSAVE_TICKS);
        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            vaultProvider = VaultEconomy.register(plugin, this, bank);
            plugin.getLogger().info("Экономика зарегистрирована в Vault");
        } else {
            plugin.getLogger().warning("Vault не найден — другие плагины не увидят экономику VladCore");
        }
        Bukkit.getOnlinePlayers().forEach(p -> bank.create(p.getUniqueId()));
        listen(this);
        placeholder("balance", p -> format(bank.balance(p.getUniqueId())));
        placeholder("balance_raw", p -> String.format(Locale.ROOT, "%.2f", bank.balance(p.getUniqueId())));
        placeholder("baltop_rank", p -> {
            List<Map.Entry<UUID, Double>> top = cachedTop();
            for (int i = 0; i < top.size(); i++) {
                if (top.get(i).getKey().equals(p.getUniqueId())) {
                    return String.valueOf(i + 1);
                }
            }
            return "-";
        });
        placeholderPrefix("baltop_name_", (p, n) -> topEntry(n, e -> {
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            return name == null ? "?" : name;
        }));
        placeholderPrefix("baltop_balance_", (p, n) -> topEntry(n, e -> format(e.getValue())));
        command(new BalanceCommand());
        command(new PayCommand());
        command(new TopCommand());
        command(new EcoCommand());
    }

    @Override
    protected void onDisable() {
        if (autosave != null) {
            autosave.cancel();
        }
        if (vaultProvider != null) {
            VaultEconomy.unregister(vaultProvider);
        }
        bank.save();
    }

    private List<Map.Entry<UUID, Double>> topCache = Collections.emptyList();
    private long topCachedAt;

    /** Топ для плейсхолдеров: пересортировка не чаще раза в 5 секунд. */
    private synchronized List<Map.Entry<UUID, Double>> cachedTop() {
        long now = System.currentTimeMillis();
        if (now - topCachedAt > 5000) {
            topCache = bank.top();
            topCachedAt = now;
        }
        return topCache;
    }

    /** Место N (с 1) в топе → текст, либо "-", если места нет. */
    private String topEntry(String place, Function<Map.Entry<UUID, Double>, String> text) {
        try {
            int n = Integer.parseInt(place);
            List<Map.Entry<UUID, Double>> top = cachedTop();
            return n >= 1 && n <= top.size() ? text.apply(top.get(n - 1)) : "-";
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        bank.create(event.getPlayer().getUniqueId());
    }

    String format(double amount) {
        DecimalFormat df = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.ROOT));
        String number = df.format(amount).replace(',', ' ');
        return plugin.getConfig().getString("economy.format", "{amount}$").replace("{amount}", number);
    }

    /** Сумма > 0 с не более чем двумя знаками, либо null с сообщением. */
    private Double parseAmount(CommandSender sender, String raw) {
        try {
            double value = Bank.round(Double.parseDouble(raw.replace(',', '.')));
            if (value > 0 && !Double.isInfinite(value)) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // упадём в сообщение ниже
        }
        plugin.messages().send(sender, "economy.bad-amount");
        return null;
    }

    /** Игрок онлайн или когда-либо заходивший; null с сообщением, если такого нет. */
    private OfflinePlayer findPlayer(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && bank.has(cached.getUniqueId())) {
            return cached;
        }
        plugin.messages().send(sender, "player-not-found", "player", name);
        return null;
    }

    private final class BalanceCommand extends ModuleCommand {
        BalanceCommand() {
            super(EconomyModule.this.plugin, "balance", "vcore.balance", "/balance [игрок]", "bal", "money");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length > 0) {
                if (!sender.hasPermission("vcore.balance.others")) {
                    plugin.messages().send(sender, "no-permission");
                    return;
                }
                OfflinePlayer target = findPlayer(sender, args[0]);
                if (target != null) {
                    plugin.messages().send(sender, "economy.balance-other", "player", target.getName(),
                            "amount", format(bank.balance(target.getUniqueId())));
                }
                return;
            }
            Player player = player(sender);
            if (player != null) {
                plugin.messages().send(player, "economy.balance", "amount", format(bank.balance(player.getUniqueId())));
            }
        }
    }

    private final class PayCommand extends ModuleCommand {
        PayCommand() {
            super(EconomyModule.this.plugin, "pay", "vcore.pay", "/pay <игрок> <сумма>");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            if (args.length < 2) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            OfflinePlayer target = findPlayer(sender, args[0]);
            Double amount = target == null ? null : parseAmount(sender, args[1]);
            if (amount == null) {
                return;
            }
            if (target.getUniqueId().equals(player.getUniqueId())) {
                plugin.messages().send(sender, "economy.pay-self");
                return;
            }
            if (!bank.transfer(player.getUniqueId(), target.getUniqueId(), amount)) {
                plugin.messages().send(sender, "economy.not-enough");
                return;
            }
            plugin.messages().send(player, "economy.paid", "player", target.getName(), "amount", format(amount));
            if (target.getPlayer() != null) {
                plugin.messages().send(target.getPlayer(), "economy.received",
                        "player", player.getName(), "amount", format(amount));
            }
        }
    }

    private final class TopCommand extends ModuleCommand {
        TopCommand() {
            super(EconomyModule.this.plugin, "baltop", "vcore.baltop", "/baltop [страница]", "moneytop");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (sender instanceof Player) {
                new TopMenu((Player) sender).open();
                return;
            }
            List<Map.Entry<UUID, Double>> top = bank.top();
            int pages = Math.max(1, (top.size() + TOP_PAGE_SIZE - 1) / TOP_PAGE_SIZE);
            int page = 1;
            if (args.length > 0) {
                try {
                    page = Integer.parseInt(args[0]);
                } catch (NumberFormatException ignored) {
                    // оставим первую
                }
            }
            page = Math.min(Math.max(page, 1), pages);
            plugin.messages().send(sender, "economy.top-header", "page", String.valueOf(page), "pages", String.valueOf(pages));
            int from = (page - 1) * TOP_PAGE_SIZE;
            for (int i = from; i < Math.min(from + TOP_PAGE_SIZE, top.size()); i++) {
                Map.Entry<UUID, Double> entry = top.get(i);
                String name = Bukkit.getOfflinePlayer(entry.getKey()).getName();
                sender.sendMessage(plugin.messages().get("economy.top-line", "place", String.valueOf(i + 1),
                        "player", name == null ? "?" : name, "amount", format(entry.getValue())));
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            return Collections.emptyList();
        }
    }

    private final class EcoCommand extends ModuleCommand {
        private final List<String> actions = Arrays.asList("give", "take", "set");

        EcoCommand() {
            super(EconomyModule.this.plugin, "eco", "vcore.eco", "/eco <give|take|set> <игрок> <сумма>");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length < 3 || !actions.contains(args[0].toLowerCase())) {
                plugin.messages().send(sender, "usage", "usage", getUsage());
                return;
            }
            OfflinePlayer target = findPlayer(sender, args[1]);
            Double amount = target == null ? null : parseAmount(sender, args[2]);
            if (amount == null) {
                return;
            }
            UUID id = target.getUniqueId();
            switch (args[0].toLowerCase()) {
                case "give":
                    bank.add(id, amount);
                    break;
                case "take":
                    bank.set(id, Math.max(0, bank.balance(id) - amount));
                    break;
                default:
                    bank.set(id, amount);
            }
            plugin.messages().send(sender, "economy.eco-done", "player", target.getName(),
                    "amount", format(bank.balance(id)));
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (!sender.hasPermission(getPermission())) {
                return Collections.emptyList();
            }
            if (args.length == 1) {
                return actions.stream().filter(a -> a.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
            }
            return args.length == 2 ? onlineNames(sender, args[1]) : Collections.emptyList();
        }
    }

    private final class TopMenu extends PagedMenu<Map.Entry<UUID, Double>> {
        TopMenu(Player viewer) {
            super(viewer, plugin.messages().get("economy.menu-title"));
        }

        @Override
        protected List<Map.Entry<UUID, Double>> entries() {
            return bank.top();
        }

        @Override
        protected ItemStack icon(Map.Entry<UUID, Double> entry, int index) {
            OfflinePlayer player = Bukkit.getOfflinePlayer(entry.getKey());
            String name = player.getName() == null ? "?" : player.getName();
            return Items.head(player,
                    plugin.messages().get("economy.menu-item", "place", String.valueOf(index + 1), "player", name),
                    Collections.singletonList(plugin.messages().get("economy.menu-lore", "amount", format(entry.getValue()))));
        }

        @Override
        protected void onClick(Map.Entry<UUID, Double> entry, InventoryClickEvent event) {
            // Просто витрина.
        }

        @Override
        protected void bottomRow() {
            set(47, Items.of(Material.GOLD_INGOT, plugin.messages().get("economy.menu-mine",
                    "amount", format(bank.balance(viewer.getUniqueId()))), Collections.emptyList()));
        }
    }
}
