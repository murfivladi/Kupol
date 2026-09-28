package dev.evoday.crates.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class KeyRepo {

    private KeyRepo() {
    }

    public static void createTables(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS evocrates_keys (
                      uuid CHAR(36) NOT NULL,
                      crate VARCHAR(64) NOT NULL,
                      amount INT NOT NULL,
                      PRIMARY KEY (uuid, crate)
                    )""");
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS evocrates_log (
                      id INTEGER PRIMARY KEY %s,
                      uuid CHAR(36) NOT NULL,
                      name VARCHAR(16) NOT NULL,
                      crate VARCHAR(64) NOT NULL,
                      reward VARCHAR(64) NOT NULL,
                      time BIGINT NOT NULL
                    )""".formatted(isMysql(c) ? "AUTO_INCREMENT" : "AUTOINCREMENT"));
        }
    }

    private static boolean isMysql(Connection c) throws SQLException {
        return c.getMetaData().getDatabaseProductName().toLowerCase().contains("mysql")
                || c.getMetaData().getDatabaseProductName().toLowerCase().contains("mariadb");
    }

    public static Map<String, Integer> load(Connection c, UUID uuid) throws SQLException {
        Map<String, Integer> keys = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT crate, amount FROM evocrates_keys WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    keys.put(rs.getString(1), rs.getInt(2));
                }
            }
        }
        return keys;
    }

    public static int get(Connection c, UUID uuid, String crate) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT amount FROM evocrates_keys WHERE uuid = ? AND crate = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, crate);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // возвращает новое количество
    public static int add(Connection c, UUID uuid, String crate, int amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE evocrates_keys SET amount = amount + ? WHERE uuid = ? AND crate = ?")) {
            ps.setInt(1, amount);
            ps.setString(2, uuid.toString());
            ps.setString(3, crate);
            if (ps.executeUpdate() == 0) {
                try (PreparedStatement insert = c.prepareStatement(
                        "INSERT INTO evocrates_keys (uuid, crate, amount) VALUES (?, ?, ?)")) {
                    insert.setString(1, uuid.toString());
                    insert.setString(2, crate);
                    insert.setInt(3, amount);
                    insert.executeUpdate();
                }
            }
        }
        return get(c, uuid, crate);
    }

    // списывает, только если хватает. -1 если не хватило
    public static int take(Connection c, UUID uuid, String crate, int amount) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE evocrates_keys SET amount = amount - ? WHERE uuid = ? AND crate = ? AND amount >= ?")) {
            ps.setInt(1, amount);
            ps.setString(2, uuid.toString());
            ps.setString(3, crate);
            ps.setInt(4, amount);
            if (ps.executeUpdate() == 0) {
                return -1;
            }
        }
        return get(c, uuid, crate);
    }

    public static void log(Connection c, UUID uuid, String name, String crate, String reward) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO evocrates_log (uuid, name, crate, reward, time) VALUES (?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, crate);
            ps.setString(4, reward);
            ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }
}
