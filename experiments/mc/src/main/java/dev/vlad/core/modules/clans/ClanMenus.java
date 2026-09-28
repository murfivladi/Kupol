package dev.vlad.core.modules.clans;

import dev.vlad.core.VladCore;
import dev.vlad.core.gui.ConfirmMenu;
import dev.vlad.core.gui.Items;
import dev.vlad.core.gui.Menu;
import dev.vlad.core.gui.PagedMenu;
import dev.vlad.core.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * GUI кланов. Все действия выполняются через те же команды /clan — проверки прав,
 * лимитов и денег одни и те же для чата и меню; после действия меню открывается заново.
 */
final class ClanMenus {

    private final ClansModule module;
    private final VladCore plugin;

    ClanMenus(ClansModule module) {
        this.module = module;
        this.plugin = module.core();
    }

    void open(Player p) {
        Clan clan = module.store().of(p.getUniqueId());
        if (clan == null) {
            new NoClanMenu(p).open();
        } else {
            new MainMenu(p, clan).open();
        }
    }

    // ---------------- помощники ----------------

    private String msg(String key, String... placeholders) {
        return plugin.messages().get(key, placeholders);
    }

    private List<String> lore(String key, String... placeholders) {
        return Arrays.asList(msg(key, placeholders).split("\n"));
    }

    private ItemStack item(Material m, String nameKey, String loreKey, String... placeholders) {
        return Items.of(m, msg(nameKey, placeholders), loreKey == null ? Collections.emptyList() : lore(loreKey, placeholders));
    }

    /** Выполнить /clan ... от игрока и через 2 тика показать меню снова (с обновлёнными данными). */
    private void run(Player p, String command, boolean reopen) {
        p.performCommand(command);
        if (reopen) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (p.isOnline()) {
                    open(p);
                }
            }, 2L);
        }
    }

    private ItemStack clanIcon(Clan c, String nameKey, String loreKey) {
        return Items.of(Items.material(c.icon), msg(nameKey, "clan", c.name, "tag", Messages.color(c.tag)),
                lore(loreKey, "tag", Messages.color(c.tag), "clan", c.name,
                        "motto", c.motto.isEmpty() ? "-" : Messages.color(c.motto),
                        "count", String.valueOf(c.members.size()),
                        "max", String.valueOf(plugin.getConfig().getInt("clans.max-members", 20)),
                        "bank", plugin.meta().format(c.bank),
                        "leader", c.leader() == null ? "?" : ClansModule.name(c.leader()),
                        "open", msg(c.open ? "clans.open-yes" : "clans.open-no"),
                        "ff", msg(c.friendlyFire ? "module-on" : "module-off")));
    }

    private void backButton(Menu menu, int slot, Runnable back) {
        menu.setButton(slot, Items.of(Material.ARROW, msg("clans.gui.back"), Collections.emptyList()), e -> back.run());
    }

    // ---------------- без клана ----------------

    private final class NoClanMenu extends Menu {
        NoClanMenu(Player viewer) {
            super(viewer, 3, msg("clans.gui.title-none"));
        }

        @Override
        protected void render() {
            fill(this);
            double cost = plugin.getConfig().getDouble("clans.create-cost", 1000);
            set(11, item(Material.NETHER_STAR, "clans.gui.create", "clans.gui.create-lore", "cost", plugin.meta().format(cost)),
                    e -> plugin.prompts().ask(viewer, "clans.gui.ask-name", name ->
                            plugin.prompts().ask(viewer, "clans.gui.ask-tag", tag -> run(viewer, "clan create " + name + " " + tag, true))));
            Clan invite = module.pendingInvite(viewer.getUniqueId());
            if (invite != null) {
                set(13, item(Material.LIME_DYE, "clans.gui.invite-accept", "clans.gui.invite-accept-lore",
                        "clan", invite.name, "tag", Messages.color(invite.tag)), e -> run(viewer, "clan accept", true));
            } else {
                set(13, item(Material.GRAY_DYE, "clans.gui.no-invites", null));
            }
            set(15, item(Material.OAK_DOOR, "clans.gui.open-clans", "clans.gui.open-clans-lore"),
                    e -> new ClanListMenu(viewer, true).open());
            set(22, item(Material.GOLD_BLOCK, "clans.gui.top", "clans.gui.top-lore"), e -> new ClanListMenu(viewer, false).open());
        }
    }

    // ---------------- главное меню клана ----------------

    private final class MainMenu extends Menu {
        private final Clan clan;

        MainMenu(Player viewer, Clan clan) {
            super(viewer, 5, msg("clans.gui.title", "clan", clan.name));
            this.clan = clan;
        }

        @Override
        protected void render() {
            fill(this);
            Clan.Rank rank = clan.rank(viewer.getUniqueId());
            if (rank == null) { // выгнали, пока меню было открыто
                viewer.closeInventory();
                return;
            }
            boolean officer = rank.atLeast(Clan.Rank.OFFICER);
            boolean leader = rank == Clan.Rank.LEADER;

            set(4, clanIcon(clan, "clans.gui.info", "clans.gui.info-lore"));
            set(19, Items.head(viewer, msg("clans.gui.members"), lore("clans.gui.members-lore",
                    "count", String.valueOf(clan.members.size()),
                    "online", String.valueOf(clan.members.keySet().stream().filter(id -> Bukkit.getPlayer(id) != null).count()))),
                    e -> new MembersMenu(viewer, clan).open());
            set(20, item(officer ? Material.WRITABLE_BOOK : Material.BOOK, "clans.gui.invite",
                    officer ? "clans.gui.invite-lore" : "clans.gui.need-officer"),
                    e -> { if (officer) new InviteMenu(viewer, clan).open(); });
            set(21, item(Material.RED_BED, "clans.gui.home", officer ? "clans.gui.home-lore-officer" : "clans.gui.home-lore"), e -> {
                if (e.getClick().isRightClick() && officer) {
                    run(viewer, "clan sethome", true);
                } else {
                    viewer.closeInventory();
                    viewer.performCommand("clan home");
                }
            });
            set(22, item(Material.GOLD_INGOT, "clans.gui.bank", "clans.gui.bank-lore", "bank", plugin.meta().format(clan.bank)),
                    e -> new BankMenu(viewer, clan).open());
            boolean chat = module.clanChatMode(viewer.getUniqueId());
            set(23, item(chat ? Material.LIME_DYE : Material.PAPER, "clans.gui.chat", "clans.gui.chat-lore",
                    "state", msg(chat ? "module-on" : "module-off")), e -> run(viewer, "clan chat", true));
            set(24, item(Material.GOLD_BLOCK, "clans.gui.top", "clans.gui.top-lore"), e -> new ClanListMenu(viewer, false).open());
            set(25, item(leader ? Material.COMPARATOR : Material.IRON_BARS, "clans.gui.settings",
                    leader ? "clans.gui.settings-lore" : "clans.gui.need-leader"),
                    e -> { if (leader) new SettingsMenu(viewer, clan).open(); });
            set(40, item(Material.BARRIER, "clans.gui.leave", leader ? "clans.gui.leave-lore-leader" : "clans.gui.leave-lore"), e -> {
                if (leader) {
                    return;
                }
                new ConfirmMenu(viewer, msg("clans.gui.leave-confirm"), clanIcon(clan, "clans.gui.info", "clans.gui.info-lore"),
                        () -> { viewer.closeInventory(); viewer.performCommand("clan leave"); }, this::open).open();
            });
            set(44, Items.of(Material.OAK_DOOR, msg("gui.close"), Collections.emptyList()), e -> viewer.closeInventory());
        }
    }

    // ---------------- участники ----------------

    private final class MembersMenu extends PagedMenu<UUID> {
        private final Clan clan;

        MembersMenu(Player viewer, Clan clan) {
            super(viewer, msg("clans.gui.title-members", "clan", clan.name));
            this.clan = clan;
        }

        @Override
        protected List<UUID> entries() {
            return clan.members.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .map(java.util.Map.Entry::getKey).collect(Collectors.toList());
        }

        @Override
        protected ItemStack icon(UUID id, int index) {
            boolean online = Bukkit.getPlayer(id) != null;
            Clan.Rank viewerRank = clan.rank(viewer.getUniqueId());
            Clan.Rank rank = clan.rank(id);
            boolean manage = viewerRank != null && rank != null && viewerRank.atLeast(Clan.Rank.OFFICER)
                    && viewerRank.compareTo(rank) > 0;
            return Items.head(Bukkit.getOfflinePlayer(id), msg("clans.gui.member", "player", ClansModule.name(id)),
                    lore(manage ? "clans.gui.member-lore-manage" : "clans.gui.member-lore",
                            "rank", module.rankName(rank), "status", msg(online ? "clans.gui.online" : "clans.gui.offline")));
        }

        @Override
        protected void onClick(UUID id, InventoryClickEvent event) {
            Clan.Rank viewerRank = clan.rank(viewer.getUniqueId());
            Clan.Rank rank = clan.rank(id);
            if (viewerRank != null && rank != null && viewerRank.atLeast(Clan.Rank.OFFICER) && viewerRank.compareTo(rank) > 0) {
                new MemberMenu(viewer, clan, id).open();
            }
        }

        @Override
        protected void bottomRow() {
            backButton(this, 48, () -> ClanMenus.this.open(viewer));
        }
    }

    private final class MemberMenu extends Menu {
        private final Clan clan;
        private final UUID target;

        MemberMenu(Player viewer, Clan clan, UUID target) {
            super(viewer, 3, msg("clans.gui.title-member", "player", ClansModule.name(target)));
            this.clan = clan;
            this.target = target;
        }

        @Override
        protected void render() {
            fill(this);
            String name = ClansModule.name(target);
            Clan.Rank rank = clan.rank(target);
            if (rank == null) {
                new MembersMenu(viewer, clan).open();
                return;
            }
            boolean leader = clan.rank(viewer.getUniqueId()) == Clan.Rank.LEADER;
            set(4, Items.head(Bukkit.getOfflinePlayer(target), msg("clans.gui.member", "player", name),
                    lore("clans.gui.member-lore", "rank", module.rankName(rank),
                            "status", msg(Bukkit.getPlayer(target) != null ? "clans.gui.online" : "clans.gui.offline"))));
            if (leader) {
                set(11, item(Material.LIME_DYE, "clans.gui.promote",
                        rank == Clan.Rank.OFFICER ? "clans.gui.promote-leader-lore" : "clans.gui.promote-lore"), e -> {
                    if (rank == Clan.Rank.OFFICER) { // передача главенства — только с подтверждением
                        new ConfirmMenu(viewer, msg("clans.gui.transfer-confirm", "player", name), Items.head(Bukkit.getOfflinePlayer(target),
                                msg("clans.gui.member", "player", name), Collections.emptyList()),
                                () -> run(viewer, "clan promote " + name, true), this::open).open();
                    } else {
                        run(viewer, "clan promote " + name, false);
                        refreshLater();
                    }
                });
                set(13, item(Material.ORANGE_DYE, "clans.gui.demote", "clans.gui.demote-lore"), e -> {
                    run(viewer, "clan demote " + name, false);
                    refreshLater();
                });
            }
            set(15, item(Material.RED_DYE, "clans.gui.kick", "clans.gui.kick-lore"), e ->
                    new ConfirmMenu(viewer, msg("clans.gui.kick-confirm", "player", name), Items.head(Bukkit.getOfflinePlayer(target),
                            msg("clans.gui.member", "player", name), Collections.emptyList()),
                            () -> { viewer.performCommand("clan kick " + name); new MembersMenu(viewer, clan).open(); },
                            this::open).open());
            backButton(this, 22, () -> new MembersMenu(viewer, clan).open());
        }

        private void refreshLater() {
            Bukkit.getScheduler().runTaskLater(plugin, this::open, 2L);
        }
    }

    // ---------------- приглашение ----------------

    private final class InviteMenu extends PagedMenu<Player> {
        private final Clan clan;

        InviteMenu(Player viewer, Clan clan) {
            super(viewer, msg("clans.gui.title-invite"));
            this.clan = clan;
        }

        @Override
        protected List<Player> entries() {
            List<Player> list = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p != viewer && viewer.canSee(p) && module.store().of(p.getUniqueId()) == null) {
                    list.add(p);
                }
            }
            return list;
        }

        @Override
        protected ItemStack icon(Player p, int index) {
            return Items.head(p, msg("clans.gui.member", "player", p.getName()), lore("clans.gui.invite-player-lore"));
        }

        @Override
        protected void onClick(Player p, InventoryClickEvent event) {
            viewer.performCommand("clan invite " + p.getName());
        }

        @Override
        protected ItemStack emptyIcon() {
            return item(Material.BARRIER, "clans.gui.invite-empty", null);
        }

        @Override
        protected void bottomRow() {
            set(50, item(Material.NAME_TAG, "clans.gui.invite-by-name", null),
                    e -> plugin.prompts().ask(viewer, "clans.gui.ask-player", name -> run(viewer, "clan invite " + name, true)));
            backButton(this, 48, () -> ClanMenus.this.open(viewer));
        }
    }

    // ---------------- казна ----------------

    private final class BankMenu extends Menu {
        private final Clan clan;

        BankMenu(Player viewer, Clan clan) {
            super(viewer, 4, msg("clans.gui.title-bank"));
            this.clan = clan;
        }

        @Override
        protected void render() {
            fill(this);
            Clan.Rank rank = clan.rank(viewer.getUniqueId());
            boolean officer = rank != null && rank.atLeast(Clan.Rank.OFFICER);
            set(4, item(Material.GOLD_BLOCK, "clans.gui.bank", "clans.gui.bank-info",
                    "bank", plugin.meta().format(clan.bank), "balance", plugin.meta().balance(viewer)));
            int[] amounts = {100, 1000, 10000};
            for (int i = 0; i < amounts.length; i++) {
                int amount = amounts[i];
                set(10 + i, item(Material.LIME_STAINED_GLASS_PANE, "clans.gui.deposit", "clans.gui.deposit-lore",
                        "amount", plugin.meta().format(amount)), e -> { viewer.performCommand("clan deposit " + amount); open(); });
                if (officer) {
                    set(19 + i, item(Material.ORANGE_STAINED_GLASS_PANE, "clans.gui.withdraw", "clans.gui.withdraw-lore",
                            "amount", plugin.meta().format(amount)), e -> { viewer.performCommand("clan withdraw " + amount); open(); });
                }
            }
            set(13, item(Material.LIME_DYE, "clans.gui.deposit-custom", null),
                    e -> plugin.prompts().ask(viewer, "clans.gui.ask-amount", a -> { viewer.performCommand("clan deposit " + a); open(); }));
            if (officer) {
                set(22, item(Material.ORANGE_DYE, "clans.gui.withdraw-custom", null),
                        e -> plugin.prompts().ask(viewer, "clans.gui.ask-amount", a -> { viewer.performCommand("clan withdraw " + a); open(); }));
            } else {
                set(20, item(Material.IRON_BARS, "clans.gui.withdraw", "clans.gui.need-officer", "amount", ""));
            }
            backButton(this, 31, () -> ClanMenus.this.open(viewer));
        }
    }

    // ---------------- настройки (глава) ----------------

    private final class SettingsMenu extends Menu {
        private final Clan clan;

        SettingsMenu(Player viewer, Clan clan) {
            super(viewer, 3, msg("clans.gui.title-settings"));
            this.clan = clan;
        }

        @Override
        protected void render() {
            fill(this);
            if (clan.rank(viewer.getUniqueId()) != Clan.Rank.LEADER) {
                ClanMenus.this.open(viewer);
                return;
            }
            set(10, item(clan.friendlyFire ? Material.IRON_SWORD : Material.WOODEN_SWORD, "clans.gui.ff", "clans.gui.ff-lore",
                    "state", msg(clan.friendlyFire ? "module-on" : "module-off")), e -> { viewer.performCommand("clan ff"); open(); });
            set(11, item(Material.NAME_TAG, "clans.gui.tag", "clans.gui.tag-lore", "tag", Messages.color(clan.tag)),
                    e -> plugin.prompts().ask(viewer, "clans.gui.ask-tag", t -> { viewer.performCommand("clan tag " + t); open(); }));
            set(12, item(Material.WRITABLE_BOOK, "clans.gui.motto", "clans.gui.motto-lore",
                    "motto", clan.motto.isEmpty() ? "-" : Messages.color(clan.motto)),
                    e -> plugin.prompts().ask(viewer, "clans.gui.ask-motto", m -> { viewer.performCommand("clan motto " + m); open(); }));
            set(13, Items.of(Items.material(clan.icon), msg("clans.gui.icon"), lore("clans.gui.icon-lore")),
                    e -> { viewer.performCommand("clan icon"); open(); });
            set(14, item(clan.open ? Material.OAK_DOOR : Material.IRON_DOOR, "clans.gui.open", "clans.gui.open-lore",
                    "state", msg(clan.open ? "clans.open-yes" : "clans.open-no")), e -> { viewer.performCommand("clan open"); open(); });
            set(16, item(Material.TNT, "clans.gui.disband", "clans.gui.disband-lore"), e ->
                    new ConfirmMenu(viewer, msg("clans.gui.disband-confirm"), clanIcon(clan, "clans.gui.info", "clans.gui.info-lore"),
                            () -> { viewer.closeInventory(); viewer.performCommand("clan disband confirm"); }, this::open).open());
            backButton(this, 22, () -> ClanMenus.this.open(viewer));
        }
    }

    // ---------------- список / топ кланов ----------------

    private final class ClanListMenu extends PagedMenu<Clan> {
        private final boolean openOnly;

        ClanListMenu(Player viewer, boolean openOnly) {
            super(viewer, msg(openOnly ? "clans.gui.title-open" : "clans.gui.title-top"));
            this.openOnly = openOnly;
        }

        @Override
        protected List<Clan> entries() {
            return module.store().top().stream().filter(c -> !openOnly || c.open).collect(Collectors.toList());
        }

        @Override
        protected ItemStack icon(Clan c, int index) {
            boolean canJoin = c.open && module.store().of(viewer.getUniqueId()) == null;
            ItemStack icon = clanIcon(c, "clans.gui.list-item", canJoin ? "clans.gui.list-lore-join" : "clans.gui.list-lore");
            icon.setAmount(Math.max(1, Math.min(64, index + 1)));
            return icon;
        }

        @Override
        protected void onClick(Clan c, InventoryClickEvent event) {
            if (c.open && module.store().of(viewer.getUniqueId()) == null && event.getClick() != ClickType.RIGHT) {
                run(viewer, "clan join " + c.name, true);
            }
        }

        @Override
        protected ItemStack emptyIcon() {
            return item(Material.BARRIER, openOnly ? "clans.gui.open-empty" : "clans.top-empty", null);
        }

        @Override
        protected void bottomRow() {
            backButton(this, 48, () -> ClanMenus.this.open(viewer));
        }
    }

    /** Фон из стекла, чтобы меню выглядело цельно. */
    private static void fill(Menu menu) {
        ItemStack pane = Items.of(Material.BLACK_STAINED_GLASS_PANE, " ", Collections.emptyList());
        for (int i = 0; i < menu.slots(); i++) {
            menu.setButton(i, pane, null);
        }
    }
}
