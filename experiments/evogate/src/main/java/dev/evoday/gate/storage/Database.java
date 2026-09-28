package dev.evoday.gate.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Database {

    @FunctionalInterface
    public interface Query<T> {
        T run(Connection connection) throws SQLException;
    }

    private final HikariDataSource source;
    private final ExecutorService executor;
    private final boolean mysql;

    public Database(JavaPlugin plugin, ConfigurationSection config) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName(plugin.getName() + "-DB");
        mysql = "mysql".equalsIgnoreCase(config.getString("type", "sqlite"));
        if (mysql) {
            ConfigurationSection m = config.getConfigurationSection("mysql");
            hikari.setJdbcUrl("jdbc:mysql://" + m.getString("host") + ":" + m.getInt("port")
                    + "/" + m.getString("database") + "?" + m.getString("params", ""));
            hikari.setUsername(m.getString("user"));
            hikari.setPassword(m.getString("password"));
            hikari.setMaximumPoolSize(4);
        } else {
            File file = new File(plugin.getDataFolder(), "data.db");
            hikari.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            hikari.setMaximumPoolSize(1);
        }
        source = new HikariDataSource(hikari);
        executor = Executors.newFixedThreadPool(mysql ? 4 : 1, r -> {
            Thread t = new Thread(r, plugin.getName() + "-DB");
            t.setDaemon(true);
            return t;
        });
    }

    public boolean isMysql() {
        return mysql;
    }

    // блокирует, только не из main thread
    public <T> T sync(Query<T> query) throws SQLException {
        try (Connection c = source.getConnection()) {
            return query.run(c);
        }
    }

    public <T> CompletableFuture<T> async(Query<T> query) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return sync(query);
            } catch (SQLException e) {
                throw new IllegalStateException(e);
            }
        }, executor);
    }

    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        source.close();
    }
}
