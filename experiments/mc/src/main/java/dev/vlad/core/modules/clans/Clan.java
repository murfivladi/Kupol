package dev.vlad.core.modules.clans;

import org.bukkit.Location;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Клан: название, тег, участники со званиями, клан-хоум, казна, урон по своим. */
final class Clan {

    enum Rank {
        MEMBER, OFFICER, LEADER;

        boolean atLeast(Rank other) {
            return ordinal() >= other.ordinal();
        }
    }

    /** Название в нижнем регистре — ключ в хранилище. */
    final String id;
    String name;
    /** Тег с цветами (&b...), показывается в чате и табе. */
    String tag;
    final Map<UUID, Rank> members = new LinkedHashMap<>();
    Location home;
    double bank;
    /** Урон по своим. */
    boolean friendlyFire;
    /** Открытый клан: вступить можно без приглашения. */
    boolean open;
    /** Девиз, показывается в меню и /clan info. */
    String motto = "";
    /** Иконка клана в меню (материал). */
    String icon = "WHITE_BANNER";
    long created;

    Clan(String name, String tag) {
        this.id = name.toLowerCase();
        this.name = name;
        this.tag = tag;
    }

    Rank rank(UUID player) {
        return members.get(player);
    }

    UUID leader() {
        return members.entrySet().stream().filter(e -> e.getValue() == Rank.LEADER)
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }
}
