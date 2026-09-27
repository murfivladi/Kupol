package dev.vlad.core.modules.teleport;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Входящие запросы /tpa и /tpahere: цель → (отправитель → запрос). */
final class TpaRequests {

    static final class Request {
        final UUID from;
        /** true — /tpahere: телепортируется получатель к отправителю. */
        final boolean here;
        final long expiresAt;

        Request(UUID from, boolean here, long expiresAt) {
            this.from = from;
            this.here = here;
            this.expiresAt = expiresAt;
        }
    }

    private final Map<UUID, LinkedHashMap<UUID, Request>> incoming = new LinkedHashMap<>();
    private int timeoutSeconds;

    void setTimeout(int seconds) {
        this.timeoutSeconds = Math.max(5, seconds);
    }

    int timeout() {
        return timeoutSeconds;
    }

    void add(UUID from, UUID to, boolean here) {
        LinkedHashMap<UUID, Request> map = incoming.computeIfAbsent(to, k -> new LinkedHashMap<>());
        map.remove(from); // повторный запрос встаёт в конец — станет "последним"
        map.put(from, new Request(from, here, System.currentTimeMillis() + timeoutSeconds * 1000L));
    }

    /** Забирает запрос от from, или самый свежий, если from == null. */
    Request take(UUID to, UUID from) {
        LinkedHashMap<UUID, Request> map = incoming.get(to);
        if (map == null) {
            return null;
        }
        purgeExpired(map);
        Request result = null;
        if (from != null) {
            result = map.remove(from);
        } else if (!map.isEmpty()) {
            UUID last = null;
            for (UUID key : map.keySet()) {
                last = key;
            }
            result = map.remove(last);
        }
        if (map.isEmpty()) {
            incoming.remove(to);
        }
        return result;
    }

    /** Игрок вышел — убираем и его входящие, и исходящие запросы. */
    void forget(UUID player) {
        incoming.remove(player);
        incoming.values().forEach(map -> map.remove(player));
    }

    private static void purgeExpired(Map<UUID, Request> map) {
        long now = System.currentTimeMillis();
        for (Iterator<Request> it = map.values().iterator(); it.hasNext(); ) {
            if (it.next().expiresAt < now) {
                it.remove();
            }
        }
    }
}
