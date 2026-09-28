package dev.evoday.gate.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;

public final class AccountRepo {

    private AccountRepo() {
    }

    public static void createTable(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS evogate_accounts (
                      uuid CHAR(36) NOT NULL PRIMARY KEY,
                      name VARCHAR(16) NOT NULL,
                      name_lower VARCHAR(16) NOT NULL UNIQUE,
                      hash VARCHAR(255) NOT NULL,
                      reg_ip VARCHAR(45),
                      last_ip VARCHAR(45),
                      reg_date BIGINT NOT NULL,
                      last_login BIGINT NOT NULL
                    )""");
        }
    }

    public static Account byName(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, name, hash, reg_ip, last_ip, last_login FROM evogate_accounts WHERE name_lower = ?")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Account(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6));
            }
        }
    }

    public static int countByIp(Connection c, String ip) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM evogate_accounts WHERE reg_ip = ?")) {
            ps.setString(1, ip);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    // false если ник успели занять
    public static boolean insert(Connection c, UUID uuid, String name, String hash, String ip) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO evogate_accounts (uuid, name, name_lower, hash, reg_ip, last_ip, reg_date, last_login)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, name.toLowerCase(Locale.ROOT));
            ps.setString(4, hash);
            ps.setString(5, ip);
            ps.setString(6, ip);
            ps.setLong(7, now);
            ps.setLong(8, now);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            if (byName(c, name) != null) {
                return false;
            }
            throw e;
        }
    }

    public static void touchLogin(Connection c, String name, String ip) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE evogate_accounts SET last_ip = ?, last_login = ? WHERE name_lower = ?")) {
            ps.setString(1, ip);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, name.toLowerCase(Locale.ROOT));
            ps.executeUpdate();
        }
    }

    public static void resetSession(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE evogate_accounts SET last_login = 0 WHERE name_lower = ?")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            ps.executeUpdate();
        }
    }

    public static boolean setHash(Connection c, String name, String hash) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE evogate_accounts SET hash = ?, last_login = 0 WHERE name_lower = ?")) {
            ps.setString(1, hash);
            ps.setString(2, name.toLowerCase(Locale.ROOT));
            return ps.executeUpdate() > 0;
        }
    }

    public static boolean delete(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM evogate_accounts WHERE name_lower = ?")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            return ps.executeUpdate() > 0;
        }
    }
}
