import java.nio.file.Path;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.FileSystemResource;

/** Explicit, authorized test-database provisioning. Never prints connection credentials. */
public class InitializeDatabase {
    private static String required(PropertyResolver config, String name) {
        String value = config.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing dev YAML property: " + name);
        }
        return value;
    }

    public static void main(String[] args) {
        try {
            initialize(args);
        } catch (SQLException exception) {
            System.err.println("Database operation failed; SQLState=" + exception.getSQLState()
                    + ", code=" + exception.getErrorCode() + ". Credentials and server messages omitted.");
            System.exit(1);
        } catch (Exception exception) {
            System.err.println("Initialization failed (" + exception.getClass().getSimpleName()
                    + "). Check dev YAML, MySQL version, TLS and Flyway history. Details omitted.");
            System.exit(1);
        }
    }

    private static void initialize(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected local migration directory and dev YAML path");
        }
        MutablePropertySources sources = new MutablePropertySources();
        new YamlPropertySourceLoader().load("dev", new FileSystemResource(args[1]))
                .forEach(sources::addLast);
        PropertyResolver config = new PropertySourcesPropertyResolver(sources);
        String url = required(config, "spring.datasource.url");
        if (!url.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("Expected MySQL JDBC URL");
        }
        URI target = URI.create(url.substring("jdbc:".length()));
        String database = target.getPath().substring(1);
        // Identifiers cannot be JDBC placeholders. Reject delimiters and URL injection.
        if (target.getHost() == null || target.getUserInfo() != null || target.getFragment() != null
                || (target.getPort() != -1 && (target.getPort() < 1 || target.getPort() > 65535))
                || !database.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Invalid database target");
        }
        String username = required(config, "spring.datasource.username");
        String password = required(config, "spring.datasource.password");
        String prefix = "jdbc:mysql://" + target.getRawAuthority() + "/";
        String options = target.getRawQuery() == null ? "" : "?" + target.getRawQuery();
        try (Connection connection = DriverManager.getConnection(prefix + options, username, password);
                Statement statement = connection.createStatement()) {
            String version = connection.getMetaData().getDatabaseProductVersion();
            String[] parts = version.split("[.-]");
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            if (version.toLowerCase(java.util.Locale.ROOT).contains("mariadb")
                    || major < 8 || (major == 8 && minor == 0 && patch < 16)) {
                throw new IllegalArgumentException("MySQL 8.0.16+ required for enforced CHECK constraints");
            }
            System.out.println("MySQL version: " + version);
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database
                    + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            System.out.println("Database available: " + database);
        }
        Flyway flyway = Flyway.configure()
                .dataSource(prefix + database + options, username, password)
                .locations("filesystem:" + Path.of(args[0]).toAbsolutePath().normalize())
                .cleanDisabled(true).baselineOnMigrate(false).validateOnMigrate(true).load();
        var result = flyway.migrate();
        flyway.validate();
        System.out.println("Migrations applied: " + result.migrationsExecuted);
        try (Connection connection = DriverManager.getConnection(prefix + database + options, username, password);
                Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery("SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank")) {
                while (rows.next()) {
                    System.out.println("Migration version=" + rows.getString(1) + ", success=" + rows.getBoolean(2));
                }
            }
            try (ResultSet rows = statement.executeQuery("SHOW TABLES")) {
                while (rows.next()) {
                    System.out.println("Table: " + rows.getString(1));
                }
            }
            try (ResultSet rows = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
                if (!rows.next() || rows.getString(2).isBlank()) {
                    throw new IllegalStateException("TLS verification failed");
                }
                System.out.println("TLS cipher: " + rows.getString(2));
            }
            try (ResultSet rows = statement.executeQuery("SELECT @@session.time_zone")) {
                rows.next();
                System.out.println("Session time zone: " + rows.getString(1));
            }
        }
    }
}
