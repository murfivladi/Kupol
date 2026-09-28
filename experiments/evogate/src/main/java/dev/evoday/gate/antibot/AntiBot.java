package dev.evoday.gate.antibot;

import dev.evoday.gate.EvoGate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class AntiBot {

    public enum Verdict { OK, THROTTLED, UNAUTHED_LIMIT, ATTACK }

    // гравитация в майнкрафте: v = (v - 0.08) * 0.98 каждый тик
    private static final double GRAVITY = 0.08;
    private static final double DRAG = 0.98;
    private static final double EPS = 0.004;
    private static final int NEED_MATCHES = 6;
    private static final int MAX_SAMPLES = 20;

    private final EvoGate plugin;
    private final Map<String, Deque<Long>> joinsByIp = new ConcurrentHashMap<>();
    private final Deque<Long> newJoins = new ArrayDeque<>();
    private final Deque<Long> newAllowed = new ArrayDeque<>();
    private volatile long attackUntil;
    private final Map<UUID, GravityCheck> checks = new ConcurrentHashMap<>();

    public AntiBot(EvoGate plugin) {
        this.plugin = plugin;
    }

    private final class GravityCheck {
        final Consumer<Boolean> done;
        BukkitTask timeout;
        double lastDy = Double.NaN;
        int samples;
        int matches;
        boolean finished;

        GravityCheck(Consumer<Boolean> done) {
            this.done = done;
        }
    }

    // вызывается из AsyncPlayerPreLoginEvent
    public synchronized Verdict checkJoin(String ip, boolean newPlayer, int unauthedFromIp) {
        long now = System.currentTimeMillis();
        int perIp = plugin.getConfig().getInt("antibot.joins-per-ip-per-minute", 6);
        if (perIp > 0) {
            Deque<Long> q = joinsByIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
            trim(q, now);
            if (q.size() >= perIp) {
                return Verdict.THROTTLED;
            }
            q.addLast(now);
        }
        int unauthedLimit = plugin.getConfig().getInt("antibot.unauthed-per-ip", 2);
        if (unauthedLimit > 0 && unauthedFromIp >= unauthedLimit) {
            return Verdict.UNAUTHED_LIMIT;
        }
        if (!newPlayer) {
            return Verdict.OK;
        }
        trim(newJoins, now);
        newJoins.addLast(now);
        if (newJoins.size() > plugin.getConfig().getInt("antibot.attack.new-players-per-minute", 20)) {
            if (!isUnderAttack()) {
                plugin.getLogger().warning("Bot attack detected: " + newJoins.size() + " new players in a minute");
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.getOnlinePlayers().stream()
                        .filter(p -> p.hasPermission("evogate.admin"))
                        .forEach(p -> plugin.messages().send(p, "attack-started")));
            }
            attackUntil = now + plugin.getConfig().getLong("antibot.attack.duration", 5) * 60_000L;
        }
        if (isUnderAttack()) {
            trim(newAllowed, now);
            if (newAllowed.size() >= plugin.getConfig().getInt("antibot.attack.allow-new", 5)) {
                return Verdict.ATTACK;
            }
            newAllowed.addLast(now);
        }
        return Verdict.OK;
    }

    public boolean isUnderAttack() {
        return System.currentTimeMillis() < attackUntil;
    }

    private static void trim(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > 60_000L) {
            q.pollFirst();
        }
    }

    // ---------- гравитация ----------

    public boolean gravityEnabled() {
        return plugin.getConfig().getBoolean("antibot.gravity-check.enabled", true);
    }

    // игрок уже висит в воздухе без полёта. done(true) - похож на настоящий клиент
    public void startGravity(Player player, Consumer<Boolean> done) {
        GravityCheck check = new GravityCheck(done);
        checks.put(player.getUniqueId(), check);
        long wait = Math.max(3, plugin.getConfig().getLong("antibot.gravity-check.wait", 10)) * 20L;
        check.timeout = Bukkit.getScheduler().runTaskLater(plugin, () -> finish(player, check, false), wait);
    }

    public boolean isChecking(Player player) {
        return checks.containsKey(player.getUniqueId());
    }

    public void onMove(Player player, double fromY, double toY) {
        GravityCheck check = checks.get(player.getUniqueId());
        if (check == null || check.finished) {
            return;
        }
        double dy = toY - fromY;
        if (dy == 0) {
            return;
        }
        if (!Double.isNaN(check.lastDy)) {
            double expected = (check.lastDy - GRAVITY) * DRAG;
            if (Math.abs(dy - expected) < EPS) {
                check.matches++;
            }
        } else if (Math.abs(dy - (-GRAVITY * DRAG)) < EPS) {
            // первый тик падения с места
            check.matches++;
        }
        check.lastDy = dy;
        check.samples++;
        if (check.matches >= NEED_MATCHES) {
            finish(player, check, true);
        } else if (check.samples >= MAX_SAMPLES) {
            finish(player, check, false);
        }
    }

    private void finish(Player player, GravityCheck check, boolean passed) {
        if (check.finished) {
            return;
        }
        check.finished = true;
        check.timeout.cancel();
        checks.remove(player.getUniqueId(), check);
        if (player.isOnline()) {
            check.done.accept(passed);
        }
    }

    public void cancel(Player player) {
        GravityCheck check = checks.remove(player.getUniqueId());
        if (check != null) {
            check.finished = true;
            check.timeout.cancel();
        }
    }
}
