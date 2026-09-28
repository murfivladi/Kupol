package dev.vlad.core.modules.regions;

import dev.vlad.core.VladCore;
import dev.vlad.core.command.ModuleCommand;
import dev.vlad.core.module.Module;
import dev.vlad.core.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Animals;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Villager;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Приваты: выделение топориком WorldEdit → /rg claim. Защита от посторонних
 * по флагам (build, use, chest, pvp, tnt, fire); участники и владелец могут всё.
 */
public final class RegionsModule extends Module implements Listener {

    static final List<String> FLAGS = Arrays.asList("build", "use", "chest", "pvp", "tnt", "fire");
    private static final String BYPASS = "vcore.regions.bypass";
    private static final String ADMIN = "vcore.regions.admin";
    private static final Pattern NAME = Pattern.compile("[a-zA-Z0-9_\\-а-яА-ЯёЁ]{1,24}");
    /** Предметы, которыми правый клик по блоку меняет мир. */
    private static final Set<Material> BUILD_ITEMS = EnumSet.of(
            Material.FLINT_AND_STEEL, Material.FIRE_CHARGE, Material.BONE_MEAL, Material.ARMOR_STAND,
            Material.END_CRYSTAL, Material.MINECART, Material.CHEST_MINECART, Material.TNT_MINECART,
            Material.HOPPER_MINECART, Material.FURNACE_MINECART, Material.ITEM_FRAME, Material.PAINTING);

    private final RegionStore store;
    private boolean worldEdit;

    public RegionsModule(VladCore plugin) {
        super(plugin, "regions");
        this.store = new RegionStore(plugin);
    }

    @Override
    protected void onEnable() {
        store.load();
        worldEdit = Bukkit.getPluginManager().getPlugin("WorldEdit") != null;
        if (!worldEdit) {
            plugin.getLogger().warning("WorldEdit не найден — /rg claim работать не будет (нужно выделение топориком)");
        }
        listen(this);
        command(new RegionCommand());
        placeholder("region", p -> {
            Region r = p.getPlayer() == null ? null : store.at(p.getPlayer().getLocation());
            return r == null ? "-" : r.name;
        });
        placeholder("region_owner", p -> {
            Region r = p.getPlayer() == null ? null : store.at(p.getPlayer().getLocation());
            return r == null ? "-" : ownerName(r);
        });
        plugin.getLogger().info("Приватов загружено: " + store.all().size());
    }

    // ---------------- проверки ----------------

    private boolean flag(Region r, String flag) {
        Boolean value = r.flags.get(flag);
        return value != null ? value : plugin.getConfig().getBoolean("regions.default-flags." + flag, false);
    }

    /** Может ли игрок сделать действие flag в точке. */
    private boolean allowed(Player player, Location at, String flag) {
        if (player.hasPermission(BYPASS)) {
            return true;
        }
        Region r = store.at(at);
        return r == null || r.isMember(player.getUniqueId()) || flag(r, flag);
    }

    private void protect(Cancellable event, Player player, Location at, String flag) {
        if (!allowed(player, at, flag)) {
            event.setCancelled(true);
            Region r = store.at(at);
            plugin.messages().send(player, "regions.denied", "region", r == null ? "?" : r.name);
        }
    }

    private String ownerName(Region r) {
        if (r.owner == null) {
            return plugin.messages().get("regions.server");
        }
        String name = Bukkit.getOfflinePlayer(r.owner).getName();
        return name == null ? "?" : name;
    }

    /** Атакующий игрок: сам игрок или тот, кто выпустил снаряд. */
    private static Player attacker(Entity damager) {
        if (damager instanceof Player) {
            return (Player) damager;
        }
        if (damager instanceof Projectile && ((Projectile) damager).getShooter() instanceof Player) {
            return (Player) ((Projectile) damager).getShooter();
        }
        return null;
    }

    // ---------------- события игроков ----------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        protect(e, e.getPlayer(), e.getBlock().getLocation(), "build");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        protect(e, e.getPlayer(), e.getBlock().getLocation(), "build");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        protect(e, e.getPlayer(), e.getBlockClicked().getRelative(e.getBlockFace()).getLocation(), "build");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        protect(e, e.getPlayer(), e.getBlockClicked().getLocation(), "build");
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent e) {
        Block block = e.getClickedBlock();
        if (block == null || (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.PHYSICAL)) {
            return;
        }
        String flag = null;
        BlockState state = block.getState();
        if (state instanceof InventoryHolder) {
            flag = "chest";
        } else if (block.getType().isInteractable() || e.getAction() == Action.PHYSICAL) {
            flag = "use"; // двери, кнопки, рычаги, нажимные плиты...
        } else if (e.getItem() != null && (BUILD_ITEMS.contains(e.getItem().getType())
                || e.getItem().getType().name().endsWith("_SPAWN_EGG") || e.getItem().getType().name().endsWith("_BOAT"))) {
            flag = "build";
        }
        if (flag != null && !allowed(e.getPlayer(), block.getLocation(), flag)) {
            e.setCancelled(true);
            if (e.getAction() != Action.PHYSICAL) { // на плиты не спамим сообщениями
                Region r = store.at(block.getLocation());
                plugin.messages().send(e.getPlayer(), "regions.denied", "region", r == null ? "?" : r.name);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity target = e.getRightClicked();
        if (target instanceof Hanging || target instanceof ArmorStand) {
            protect(e, e.getPlayer(), target.getLocation(), "build");
        } else if (target instanceof Animals || target instanceof Villager) {
            protect(e, e.getPlayer(), target.getLocation(), "use");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent e) {
        protect(e, e.getPlayer(), e.getRightClicked().getLocation(), "build");
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Player player = attacker(e.getRemover());
        if (player != null) {
            protect(e, player, e.getEntity().getLocation(), "build");
        } else {
            Region r = store.at(e.getEntity().getLocation());
            if (r != null && !flag(r, "tnt")) {
                e.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent e) {
        Player player = e.getAttacker() == null ? null : attacker(e.getAttacker());
        if (player != null) {
            protect(e, player, e.getVehicle().getLocation(), "build");
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        Player player = attacker(e.getDamager());
        if (player == null) {
            return;
        }
        Entity victim = e.getEntity();
        if (victim instanceof Player) {
            if (victim == player || player.hasPermission(BYPASS)) {
                return;
            }
            // PvP запрещено, если флаг pvp выключен там, где стоит жертва или нападающий.
            Region at = store.at(victim.getLocation());
            Region from = store.at(player.getLocation());
            if ((at != null && !flag(at, "pvp")) || (from != null && !flag(from, "pvp"))) {
                e.setCancelled(true);
                plugin.messages().send(player, "regions.no-pvp");
            }
        } else if (victim instanceof ArmorStand || victim instanceof Hanging) {
            protect(e, player, victim.getLocation(), "build");
        } else if (victim instanceof Animals || victim instanceof Villager) {
            protect(e, player, victim.getLocation(), "use");
        }
    }

    // ---------------- мир: взрывы, огонь, поршни, жидкости ----------------

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> {
            Region r = store.at(b.getLocation());
            return r != null && !flag(r, "tnt");
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> {
            Region r = store.at(b.getLocation());
            return r != null && !flag(r, "tnt");
        });
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        if (e.getPlayer() != null) {
            protect(e, e.getPlayer(), e.getBlock().getLocation(), "build");
            return;
        }
        Region r = store.at(e.getBlock().getLocation());
        if (r != null && !flag(r, "fire")) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        Region r = store.at(e.getBlock().getLocation());
        if (r != null && !flag(r, "fire")) {
            e.setCancelled(true);
        }
    }

    /** Эндермены, иссушители, разрушители и т.п. не меняют блоки в привате. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent e) {
        if (e.getEntity() instanceof Player) {
            protect(e, (Player) e.getEntity(), e.getBlock().getLocation(), "build");
        } else if (store.at(e.getBlock().getLocation()) != null
                && e.getEntityType() != EntityType.FALLING_BLOCK) {
            e.setCancelled(true);
        }
    }

    /** Поршень не может двигать блоки через границу привата. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        Region home = store.at(e.getBlock().getLocation());
        List<Block> moved = new ArrayList<>(e.getBlocks());
        moved.add(e.getBlock().getRelative(e.getDirection())); // куда встанет голова
        for (Block b : moved) {
            if (store.at(b.getLocation()) != home || store.at(b.getRelative(e.getDirection()).getLocation()) != home) {
                e.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        Region home = store.at(e.getBlock().getLocation());
        for (Block b : e.getBlocks()) {
            if (store.at(b.getLocation()) != home) {
                e.setCancelled(true);
                return;
            }
        }
    }

    /** Вода и лава не затекают в приват снаружи (заливка лавой — классика грифа). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        Region to = store.at(e.getToBlock().getLocation());
        if (to != null && to != store.at(e.getBlock().getLocation())) {
            e.setCancelled(true);
        }
    }

    // ---------------- лимиты ----------------

    /** Наибольшее N из прав prefix.N, либо def. */
    private static int permNumber(Player player, String prefix, int def) {
        int best = def;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (info.getValue() && perm.startsWith(prefix)) {
                try {
                    best = Math.max(best, Integer.parseInt(perm.substring(prefix.length())));
                } catch (NumberFormatException ignored) {
                    // vcore.regions.limit.abc — не число, пропускаем
                }
            }
        }
        return best;
    }

    // ---------------- команды ----------------

    private final class RegionCommand extends ModuleCommand {
        private final List<String> subs = Arrays.asList(
                "claim", "delete", "info", "list", "show", "addmember", "removemember", "flag", "define", "priority");

        RegionCommand() {
            super(RegionsModule.this.plugin, "region", "vcore.regions", "/rg <claim|info|list|show|addmember|removemember|flag|delete>", "rg", "priv");
        }

        @Override
        protected void run(CommandSender sender, String[] args) {
            if (args.length == 0) {
                plugin.messages().send(sender, "regions.help");
                return;
            }
            String sub = args[0].toLowerCase(Locale.ROOT);
            String[] rest = Arrays.copyOfRange(args, 1, args.length);
            switch (sub) {
                case "claim": claim(sender, rest, false); break;
                case "define": claim(sender, rest, true); break;
                case "delete": case "remove": delete(sender, rest); break;
                case "info": info(sender, rest); break;
                case "list": list(sender, rest); break;
                case "show": show(sender, rest); break;
                case "addmember": member(sender, rest, true); break;
                case "removemember": member(sender, rest, false); break;
                case "flag": setFlag(sender, rest); break;
                case "priority": priority(sender, rest); break;
                default: plugin.messages().send(sender, "regions.help");
            }
        }

        /** Регион по имени или тот, где стоит игрок. */
        private Region region(CommandSender sender, String[] args, int index) {
            Region r;
            if (args.length > index) {
                r = store.get(args[index]);
            } else if (sender instanceof Player) {
                r = store.at(((Player) sender).getLocation());
            } else {
                r = null;
            }
            if (r == null) {
                plugin.messages().send(sender, "regions.not-found");
            }
            return r;
        }

        private boolean canManage(CommandSender sender, Region r) {
            if (sender.hasPermission(ADMIN) || (sender instanceof Player && ((Player) sender).getUniqueId().equals(r.owner))) {
                return true;
            }
            plugin.messages().send(sender, "regions.not-owner");
            return false;
        }

        private void claim(CommandSender sender, String[] args, boolean admin) {
            Player player = player(sender);
            if (player == null) {
                return;
            }
            if (admin && !player.hasPermission(ADMIN)) {
                plugin.messages().send(player, "no-permission");
                return;
            }
            if (args.length == 0) {
                plugin.messages().send(player, "usage", "usage", "/rg " + (admin ? "define" : "claim") + " <название>");
                return;
            }
            String name = args[0];
            if (!NAME.matcher(name).matches()) {
                plugin.messages().send(player, "regions.bad-name");
                return;
            }
            if (store.get(name) != null) {
                plugin.messages().send(player, "regions.name-taken", "region", name);
                return;
            }
            if (!worldEdit) {
                plugin.messages().send(player, "regions.no-worldedit");
                return;
            }
            int[] sel = WorldEditSelection.of(player);
            if (sel == null) {
                plugin.messages().send(player, "regions.no-selection");
                return;
            }
            World world = player.getWorld();
            boolean fullHeight = plugin.getConfig().getBoolean("regions.full-height", true);
            Region r = new Region(name, world.getName(), sel[0], fullHeight ? 0 : sel[1], sel[2],
                    sel[3], fullHeight ? world.getMaxHeight() - 1 : sel[4], sel[5]);

            if (!admin && !player.hasPermission(ADMIN)) {
                int minSide = plugin.getConfig().getInt("regions.min-side", 5);
                if (r.maxX - r.minX + 1 < minSide || r.maxZ - r.minZ + 1 < minSide) {
                    plugin.messages().send(player, "regions.too-small", "min", String.valueOf(minSide));
                    return;
                }
                int maxArea = permNumber(player, "vcore.regions.area.", plugin.getConfig().getInt("regions.max-area", 2500));
                if (r.area() > maxArea) {
                    plugin.messages().send(player, "regions.too-big", "area", String.valueOf(r.area()), "max", String.valueOf(maxArea));
                    return;
                }
                int limit = permNumber(player, "vcore.regions.limit.", plugin.getConfig().getInt("regions.default-limit", 2));
                if (store.ownedBy(player.getUniqueId()).size() >= limit) {
                    plugin.messages().send(player, "regions.limit", "limit", String.valueOf(limit));
                    return;
                }
                // Граница мира: приват за ней бессмыслен.
                if (!world.getWorldBorder().isInside(new Location(world, r.minX, 64, r.minZ))
                        || !world.getWorldBorder().isInside(new Location(world, r.maxX, 64, r.maxZ))) {
                    plugin.messages().send(player, "regions.outside-border");
                    return;
                }
            }
            if (!admin) {
                List<Region> overlap = store.overlapping(r);
                if (!overlap.isEmpty()) {
                    plugin.messages().send(player, "regions.overlap", "region", overlap.get(0).name);
                    return;
                }
                r.owner = player.getUniqueId();
            } else {
                r.priority = 10; // админские (спавн) важнее вложенных приватов
            }
            r.created = System.currentTimeMillis();
            store.add(r);
            store.save();
            plugin.messages().send(player, "regions.claimed", "region", name,
                    "x", String.valueOf(r.maxX - r.minX + 1), "z", String.valueOf(r.maxZ - r.minZ + 1));
            showBorder(player, r);
        }

        private void delete(CommandSender sender, String[] args) {
            Region r = region(sender, args, 0);
            if (r == null || !canManage(sender, r)) {
                return;
            }
            store.remove(r);
            store.save();
            plugin.messages().send(sender, "regions.deleted", "region", r.name);
        }

        private void info(CommandSender sender, String[] args) {
            Region r = region(sender, args, 0);
            if (r == null) {
                return;
            }
            List<String> members = r.members.stream().map(Bukkit::getOfflinePlayer)
                    .map(OfflinePlayer::getName).map(n -> n == null ? "?" : n).collect(Collectors.toList());
            String flags = FLAGS.stream().map(f -> (flag(r, f) ? "&a" : "&c") + f).collect(Collectors.joining("&7, "));
            plugin.messages().send(sender, "regions.info",
                    "region", r.name, "owner", ownerName(r),
                    "members", members.isEmpty() ? "-" : String.join(", ", members),
                    "size", (r.maxX - r.minX + 1) + "×" + (r.maxZ - r.minZ + 1),
                    "from", r.minX + ", " + r.minZ, "to", r.maxX + ", " + r.maxZ,
                    "flags", Messages.color(flags));
            if (sender instanceof Player) {
                showBorder((Player) sender, r);
            }
        }

        private void list(CommandSender sender, String[] args) {
            UUID owner;
            if (args.length > 0 && sender.hasPermission(ADMIN)) {
                owner = Bukkit.getOfflinePlayer(args[0]).getUniqueId();
            } else if (sender instanceof Player) {
                owner = ((Player) sender).getUniqueId();
            } else {
                plugin.messages().send(sender, "usage", "usage", "/rg list <игрок>");
                return;
            }
            List<String> names = store.ownedBy(owner).stream().map(r -> r.name).collect(Collectors.toList());
            plugin.messages().send(sender, names.isEmpty() ? "regions.list-empty" : "regions.list",
                    "regions", String.join(", ", names), "count", String.valueOf(names.size()));
        }

        private void show(CommandSender sender, String[] args) {
            Player player = player(sender);
            Region r = player == null ? null : region(sender, args, 0);
            if (r != null) {
                showBorder(player, r);
                plugin.messages().send(player, "regions.showing", "region", r.name);
            }
        }

        private void member(CommandSender sender, String[] args, boolean add) {
            if (args.length < 2) {
                plugin.messages().send(sender, "usage", "usage", "/rg " + (add ? "addmember" : "removemember") + " <приват> <игрок>");
                return;
            }
            Region r = region(sender, args, 0);
            if (r == null || !canManage(sender, r)) {
                return;
            }
            OfflinePlayer target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                target = Bukkit.getOfflinePlayerIfCached(args[1]);
            }
            if (target == null) {
                plugin.messages().send(sender, "player-not-found", "player", args[1]);
                return;
            }
            boolean changed = add ? r.members.add(target.getUniqueId()) : r.members.remove(target.getUniqueId());
            if (changed) {
                store.save();
            }
            plugin.messages().send(sender, add ? "regions.member-added" : "regions.member-removed",
                    "player", String.valueOf(target.getName()), "region", r.name);
        }

        private void setFlag(CommandSender sender, String[] args) {
            if (args.length < 3 || !FLAGS.contains(args[1].toLowerCase(Locale.ROOT))) {
                plugin.messages().send(sender, "usage", "usage", "/rg flag <приват> <" + String.join("|", FLAGS) + "> <allow|deny|reset>");
                return;
            }
            Region r = region(sender, args, 0);
            if (r == null || !canManage(sender, r)) {
                return;
            }
            String flag = args[1].toLowerCase(Locale.ROOT);
            // Какие флаги можно менять игрокам (например, tnt на гриф-сервере — только админам).
            if (!sender.hasPermission(ADMIN) && !plugin.getConfig().getStringList("regions.player-flags").contains(flag)) {
                plugin.messages().send(sender, "regions.flag-admin-only", "flag", flag);
                return;
            }
            String value = args[2].toLowerCase(Locale.ROOT);
            if (value.equals("reset")) {
                r.flags.remove(flag);
            } else if (value.equals("allow") || value.equals("deny")) {
                r.flags.put(flag, value.equals("allow"));
            } else {
                plugin.messages().send(sender, "usage", "usage", "/rg flag <приват> <флаг> <allow|deny|reset>");
                return;
            }
            store.save();
            plugin.messages().send(sender, "regions.flag-set", "region", r.name, "flag", flag,
                    "value", plugin.messages().get(flag(r, flag) ? "regions.allow" : "regions.deny"));
        }

        private void priority(CommandSender sender, String[] args) {
            if (!sender.hasPermission(ADMIN)) {
                plugin.messages().send(sender, "no-permission");
                return;
            }
            if (args.length < 2) {
                plugin.messages().send(sender, "usage", "usage", "/rg priority <приват> <число>");
                return;
            }
            Region r = region(sender, args, 0);
            if (r == null) {
                return;
            }
            try {
                r.priority = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                plugin.messages().send(sender, "usage", "usage", "/rg priority <приват> <число>");
                return;
            }
            store.save();
            plugin.messages().send(sender, "regions.priority-set", "region", r.name, "priority", String.valueOf(r.priority));
        }

        @Override
        public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (args.length == 1) {
                return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
            }
            String sub = args[0].toLowerCase();
            if (args.length == 2 && !sub.equals("claim") && !sub.equals("define")) {
                String prefix = args[1].toLowerCase();
                return store.all().stream()
                        .filter(r -> sender.hasPermission(ADMIN) || (sender instanceof Player
                                && r.isMember(((Player) sender).getUniqueId())))
                        .map(r -> r.name).filter(n -> n.toLowerCase().startsWith(prefix)).collect(Collectors.toList());
            }
            if (args.length == 3 && (sub.equals("addmember") || sub.equals("removemember"))) {
                return onlineNames(sender, args[2]);
            }
            if (args.length == 3 && sub.equals("flag")) {
                return FLAGS.stream().filter(f -> f.startsWith(args[2].toLowerCase())).collect(Collectors.toList());
            }
            if (args.length == 4 && sub.equals("flag")) {
                return Arrays.asList("allow", "deny", "reset");
            }
            return Collections.emptyList();
        }
    }

    // ---------------- показ границ ----------------

    /** 10 секунд зелёные частицы по периметру привата — видно только этому игроку. */
    private void showBorder(Player player, Region r) {
        final int[] runs = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || runs[0]++ >= 20 || !player.getWorld().getName().equals(r.world)) {
                task[0].cancel();
                return;
            }
            double y = player.getLocation().getY() + 1;
            Location base = player.getLocation();
            List<double[]> points = new ArrayList<>();
            for (int x = r.minX; x <= r.maxX + 1; x++) {
                points.add(new double[]{x, r.minZ});
                points.add(new double[]{x, r.maxZ + 1});
            }
            for (int z = r.minZ; z <= r.maxZ + 1; z++) {
                points.add(new double[]{r.minX, z});
                points.add(new double[]{r.maxX + 1, z});
            }
            for (double[] p : points) {
                double dx = p[0] - base.getX();
                double dz = p[1] - base.getZ();
                if (dx * dx + dz * dz <= 48 * 48) { // только рядом с игроком
                    player.spawnParticle(Particle.VILLAGER_HAPPY, p[0], y, p[1], 1, 0, 0, 0, 0);
                }
            }
        }, 0L, 10L);
    }
}
