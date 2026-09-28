package dev.vlad.core.modules.auction;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.gui.ConfirmMenu;
import dev.vlad.core.gui.Items;
import dev.vlad.core.gui.PagedMenu;
import dev.vlad.core.module.Module;
import dev.vlad.core.modules.auction.AuctionStore.Listing;
import dev.vlad.core.storage.PlayerData;
import dev.vlad.core.util.Durations;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Аукцион /ah: выставить предмет из руки, купить в меню, свои лоты, хранилище
 * (просроченные/снятые/не влезшие предметы). Деньги — через Vault, с налогом.
 */
public final class AuctionModule extends Module implements Listener {

    private enum Sort { NEWEST, CHEAPEST, EXPENSIVE }

    private final AuctionStore store;
    private final Map<UUID, Sort> sort = new HashMap<>();
    private BukkitTask expiry;

    public AuctionModule(VladCore plugin) {
        super(plugin, "auction");
        this.store = new AuctionStore(plugin);
    }

    @Override
    protected void onEnable() {
        store.load();
        listen(this);
        command(new AuctionCommand());
        placeholder("auction_listings", p -> String.valueOf(store.all().size()));
        placeholder("auction_my", p -> String.valueOf(store.bySeller(p.getUniqueId()).size()));
        expiry = Bukkit.getScheduler().runTaskTimer(plugin, this::expire, 20L * 30, 20L * 60);
        plugin.getLogger().info("Лотов на аукционе: " + store.all().size());
    }

    @Override
    protected void onDisable() {
        if (expiry != null) {
            expiry.cancel();
        }
        store.save();
    }

    // ---------------- логика ----------------

    private String msg(String key, String... p) {
        return plugin.messages().get(key, p);
    }

    private double tax() {
        return Math.max(0, Math.min(100, plugin.getConfig().getDouble("auction.tax-percent", 5))) / 100.0;
    }

    private int limit(Player p) {
        int best = plugin.getConfig().getInt("auction.default-limit", 5);
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (info.getValue() && perm.startsWith("vcore.auction.limit.")) {
                try {
                    best = Math.max(best, Integer.parseInt(perm.substring("vcore.auction.limit.".length())));
                } catch (NumberFormatException ignored) {
                    // не число
                }
            }
        }
        return best;
    }

    /** Отдать предметы игроку; что не влезло — в хранилище. */
    private void give(Player p, ItemStack item) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        if (!left.isEmpty()) {
            store.storage(p.getUniqueId()).addAll(left.values());
            store.save();
            plugin.messages().send(p, "auction.to-storage");
        }
    }

    /** Просроченные лоты — в хранилище продавца. */
    private void expire() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Listing l : store.all()) {
            if (l.expires <= now) {
                store.remove(l.id);
                store.storage(l.seller).add(l.item);
                changed = true;
                Player seller = Bukkit.getPlayer(l.seller);
                if (seller != null) {
                    plugin.messages().send(seller, "auction.expired", "item", itemName(l.item));
                }
            }
        }
        if (changed) {
            store.save();
        }
    }

    private void buy(Player buyer, String id) {
        Listing l = store.get(id); // заново: пока было открыто подтверждение, лот могли купить
        if (l == null) {
            plugin.messages().send(buyer, "auction.gone");
            return;
        }
        if (l.seller.equals(buyer.getUniqueId())) {
            plugin.messages().send(buyer, "auction.own");
            return;
        }
        if (!plugin.meta().withdraw(buyer, l.price)) {
            plugin.messages().send(buyer, "auction.not-enough", "price", plugin.meta().format(l.price));
            return;
        }
        store.remove(id);
        double income = Math.round(l.price * (1 - tax()) * 100) / 100.0;
        plugin.meta().deposit(Bukkit.getOfflinePlayer(l.seller), income);
        give(buyer, l.item.clone());
        store.save();
        plugin.messages().send(buyer, "auction.bought", "item", itemName(l.item), "price", plugin.meta().format(l.price));
        Player seller = Bukkit.getPlayer(l.seller);
        if (seller != null) {
            plugin.messages().send(seller, "auction.sold", "item", itemName(l.item), "player", buyer.getName(),
                    "income", plugin.meta().format(income));
        } else {
            // Сводка при следующем входе.
            PlayerData data = plugin.players().get(l.seller);
            data.set("auction.offline-sold", (int) data.getLong("auction.offline-sold", 0) + 1);
            data.set("auction.offline-income", data.getDouble("auction.offline-income", 0) + income);
        }
    }

    private static String itemName(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        String name = meta != null && meta.hasDisplayName() ? meta.getDisplayName()
                : item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return item.getAmount() > 1 ? name + " §7x" + item.getAmount() : name;
    }

    /** Предмет лота с дописанным описанием (цена, продавец, время). */
    private ItemStack display(Listing l, String hintKey) {
        ItemStack icon = l.item.clone();
        ItemMeta meta = icon.getItemMeta();
        if (meta == null) {
            return icon;
        }
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        String seller = Bukkit.getOfflinePlayer(l.seller).getName();
        lore.addAll(Arrays.asList(msg("auction.lore", "price", plugin.meta().format(l.price),
                "seller", seller == null ? "?" : seller,
                "time", Durations.format(Math.max(0, l.expires - System.currentTimeMillis()))).split("\n")));
        lore.addAll(Arrays.asList(msg(hintKey).split("\n")));
        meta.setLore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        PlayerData data = plugin.players().get(e.getPlayer().getUniqueId());
        long sold = data.getLong("auction.offline-sold", 0);
        if (sold > 0) {
            double income = data.getDouble("auction.offline-income", 0);
            data.set("auction.offline-sold", null);
            data.set("auction.offline-income", null);
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.messages().send(e.getPlayer(), "auction.offline-summary",
                    "count", String.valueOf(sold), "income", plugin.meta().format(income)), 40L);
        }
        if (!store.storage(e.getPlayer().getUniqueId()).isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.messages().send(e.getPlayer(), "auction.storage-reminder"), 60L);
        } else {
            store.cleanStorage(e.getPlayer().getUniqueId());
        }
    }

    // ---------------- меню ----------------

    private final class MarketMenu extends PagedMenu<Listing> {
        MarketMenu(Player viewer) {
            super(viewer, msg("auction.gui.title"));
        }

        @Override
        protected List<Listing> entries() {
            List<Listing> list = store.all();
            switch (sort.getOrDefault(viewer.getUniqueId(), Sort.NEWEST)) {
                case CHEAPEST: list.sort(Comparator.comparingDouble(l -> l.price)); break;
                case EXPENSIVE: list.sort(Comparator.comparingDouble((Listing l) -> l.price).reversed()); break;
                default: list.sort(Comparator.comparingLong((Listing l) -> l.created).reversed());
            }
            return list;
        }

        @Override
        protected ItemStack icon(Listing l, int index) {
            return display(l, l.seller.equals(viewer.getUniqueId()) ? "auction.hint-own" : "auction.hint-buy");
        }

        @Override
        protected void onClick(Listing l, InventoryClickEvent event) {
            if (l.seller.equals(viewer.getUniqueId())) {
                new MyMenu(viewer).open();
                return;
            }
            new ConfirmMenu(viewer, msg("auction.gui.confirm", "price", plugin.meta().format(l.price)),
                    display(l, "auction.hint-confirm"),
                    () -> { buy(viewer, l.id); open(); }, this::open).open();
        }

        @Override
        protected ItemStack emptyIcon() {
            return Items.of(Material.BARRIER, msg("auction.gui.empty"), Arrays.asList(msg("auction.gui.empty-lore").split("\n")));
        }

        @Override
        protected void bottomRow() {
            Sort current = sort.getOrDefault(viewer.getUniqueId(), Sort.NEWEST);
            set(46, Items.of(Material.HOPPER, msg("auction.gui.sort"),
                    Arrays.asList(msg("auction.gui.sort-lore", "sort", msg("auction.gui.sort-" + current.name().toLowerCase(Locale.ROOT))).split("\n"))),
                    e -> {
                        sort.put(viewer.getUniqueId(), Sort.values()[(current.ordinal() + 1) % Sort.values().length]);
                        refresh();
                    });
            set(47, Items.of(Material.CHEST, msg("auction.gui.my"), Arrays.asList(msg("auction.gui.my-lore",
                    "count", String.valueOf(store.bySeller(viewer.getUniqueId()).size()),
                    "limit", String.valueOf(limit(viewer))).split("\n"))), e -> new MyMenu(viewer).open());
            int stored = store.storage(viewer.getUniqueId()).size();
            set(51, Items.of(stored > 0 ? Material.ENDER_CHEST : Material.BARREL, msg("auction.gui.storage"),
                    Arrays.asList(msg("auction.gui.storage-lore", "count", String.valueOf(stored)).split("\n"))),
                    e -> new StorageMenu(viewer).open());
            set(52, Items.of(Material.BOOK, msg("auction.gui.help"), Arrays.asList(msg("auction.gui.help-lore",
                    "tax", String.valueOf((int) Math.round(tax() * 100))).split("\n"))));
        }
    }

    private final class MyMenu extends PagedMenu<Listing> {
        MyMenu(Player viewer) {
            super(viewer, msg("auction.gui.title-my"));
        }

        @Override
        protected List<Listing> entries() {
            return store.bySeller(viewer.getUniqueId());
        }

        @Override
        protected ItemStack icon(Listing l, int index) {
            return display(l, "auction.hint-cancel");
        }

        @Override
        protected void onClick(Listing l, InventoryClickEvent event) {
            if (store.remove(l.id) != null) {
                give(viewer, l.item.clone());
                store.save();
                plugin.messages().send(viewer, "auction.cancelled", "item", itemName(l.item));
            }
            refresh();
        }

        @Override
        protected ItemStack emptyIcon() {
            return Items.of(Material.BARRIER, msg("auction.gui.my-empty"), Collections.emptyList());
        }

        @Override
        protected void bottomRow() {
            set(48, Items.of(Material.ARROW, msg("auction.gui.back"), Collections.emptyList()), e -> new MarketMenu(viewer).open());
        }
    }

    private final class StorageMenu extends PagedMenu<ItemStack> {
        StorageMenu(Player viewer) {
            super(viewer, msg("auction.gui.title-storage"));
        }

        @Override
        protected List<ItemStack> entries() {
            return new ArrayList<>(store.storage(viewer.getUniqueId()));
        }

        @Override
        protected ItemStack icon(ItemStack item, int index) {
            ItemStack icon = item.clone();
            ItemMeta meta = icon.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.addAll(Arrays.asList(msg("auction.hint-claim").split("\n")));
                meta.setLore(lore);
                icon.setItemMeta(meta);
            }
            return icon;
        }

        @Override
        protected void onClick(ItemStack item, InventoryClickEvent event) {
            List<ItemStack> items = store.storage(viewer.getUniqueId());
            // Ищем именно этот экземпляр — кнопка могла устареть.
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i) == item) {
                    items.remove(i);
                    Map<Integer, ItemStack> left = viewer.getInventory().addItem(item.clone());
                    items.addAll(left.values());
                    if (!left.isEmpty()) {
                        plugin.messages().send(viewer, "auction.inventory-full");
                    }
                    store.save();
                    break;
                }
            }
            refresh();
        }

        @Override
        protected ItemStack emptyIcon() {
            return Items.of(Material.BARRIER, msg("auction.gui.storage-empty"), Collections.emptyList());
        }

        @Override
        protected void bottomRow() {
            set(48, Items.of(Material.ARROW, msg("auction.gui.back"), Collections.emptyList()), e -> new MarketMenu(viewer).open());
        }
    }

    // ---------------- команда ----------------

    private final class AuctionCommand extends ModuleCommand {
        AuctionCommand() {
            super(AuctionModule.this.plugin, "auction", "vcore.auction", "/ah [sell <цена>]", "ah", "auc");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            Player p = player(sender);
            if (p == null) {
                return;
            }
            if (args.length == 0) {
                new MarketMenu(p).open();
                return;
            }
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "sell": sell(p, args); break;
                case "my": new MyMenu(p).open(); break;
                case "storage": new StorageMenu(p).open(); break;
                default: plugin.messages().send(p, "auction.help");
            }
        }

        private void sell(Player p, String[] args) {
            if (args.length < 2) {
                plugin.messages().send(p, "usage", "usage", "/ah sell <цена>");
                return;
            }
            if (!plugin.meta().economyAvailable()) {
                plugin.messages().send(p, "auction.no-economy");
                return;
            }
            // Предметы из креатива на аукцион — готовый дюп денег.
            if (p.getGameMode() == GameMode.CREATIVE && !p.hasPermission("vcore.auction.creative")) {
                plugin.messages().send(p, "auction.creative");
                return;
            }
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                plugin.messages().send(p, "auction.empty-hand");
                return;
            }
            if (plugin.getConfig().getStringList("auction.blacklist").contains(hand.getType().name())) {
                plugin.messages().send(p, "auction.blacklisted");
                return;
            }
            double price;
            try {
                price = Math.round(Double.parseDouble(args[1].replace(',', '.')) * 100) / 100.0;
            } catch (NumberFormatException e) {
                price = -1;
            }
            double min = plugin.getConfig().getDouble("auction.min-price", 1);
            double max = plugin.getConfig().getDouble("auction.max-price", 1_000_000_000);
            if (price < min || price > max || Double.isNaN(price)) {
                plugin.messages().send(p, "auction.bad-price", "min", plugin.meta().format(min), "max", plugin.meta().format(max));
                return;
            }
            int limit = limit(p);
            if (store.bySeller(p.getUniqueId()).size() >= limit) {
                plugin.messages().send(p, "auction.limit", "limit", String.valueOf(limit));
                return;
            }
            ItemStack item = hand.clone();
            p.getInventory().setItemInMainHand(null); // сначала забираем предмет, потом создаём лот
            long now = System.currentTimeMillis();
            long hours = plugin.getConfig().getLong("auction.duration-hours", 48);
            String id = Long.toString(now, 36) + UUID.randomUUID().toString().substring(0, 4);
            store.add(new Listing(id, p.getUniqueId(), item, price, now, now + hours * 3_600_000L));
            store.save();
            plugin.messages().send(p, "auction.listed", "item", itemName(item), "price", plugin.meta().format(price),
                    "tax", String.valueOf((int) Math.round(tax() * 100)));
            if (plugin.getConfig().getBoolean("auction.broadcast", true)) {
                String text = plugin.messages().get("prefix") + msg("auction.broadcast", "player", p.getName(),
                        "item", itemName(item), "price", plugin.meta().format(price));
                Bukkit.getOnlinePlayers().stream().filter(o -> o != p).forEach(o -> o.sendMessage(text));
            }
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length == 1) {
                return Arrays.asList("sell", "my", "storage").stream()
                        .filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
            }
            return Collections.emptyList();
        }
    }
}
