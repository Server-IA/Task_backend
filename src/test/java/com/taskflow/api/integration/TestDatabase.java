package com.taskflow.api.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Una base de datos PostgreSQL nueva y descartable para las pruebas de integración, en el mismo servidor de
 * {@code DB_URL} (variables de entorno o el archivo {@code .env}). Se crea una sola vez por ejecución y se elimina al
 * terminar. Si no hay un PostgreSQL disponible, {@link #available()} es {@code false} y las pruebas se omiten.
 */
final class TestDatabase {

    private static String adminUrl;
    private static String user;
    private static String password;
    private static String url;
    private static String name;
    private static boolean initialized;

    private TestDatabase() {
    }

    static synchronized boolean available() {
        init();
        return url != null;
    }

    static String url() {
        init();
        return url;
    }

    static String user() {
        return user;
    }

    static String password() {
        return password;
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(url(), user, password);
    }

    private static synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        Map<String, String> env = settings();
        adminUrl = env.get("DB_URL");
        user = env.get("DB_USER");
        password = env.get("DB_PASSWORD");
        if (adminUrl == null || !adminUrl.startsWith("jdbc:postgresql:")) {
            return;
        }
        name = "taskflow_it_" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException ex) {
            return;
        }
        url = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + name;
        Runtime.getRuntime().addShutdownHook(new Thread(TestDatabase::drop));
    }

    private static void drop() {
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password);
             Statement statement = admin.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
        } catch (SQLException ignored) {
            // La base de pruebas se queda; se puede borrar a mano (taskflow_it_*)
        }
    }

    /** {@code DB_URL}, {@code DB_USER} y {@code DB_PASSWORD} del entorno o, si no están, del archivo {@code .env}. */
    private static Map<String, String> settings() {
        Map<String, String> values = new HashMap<>();
        Path file = Path.of(".env");
        if (Files.exists(file)) {
            try {
                for (String line : Files.readAllLines(file)) {
                    int equals = line.indexOf('=');
                    if (equals > 0 && !line.startsWith("#")) {
                        values.put(line.substring(0, equals).trim(), line.substring(equals + 1).trim());
                    }
                }
            } catch (IOException ignored) {
                // Sin .env se usan las variables de entorno
            }
        }
        for (String key : new String[] {"DB_URL", "DB_USER", "DB_PASSWORD"}) {
            String fromEnv = System.getenv(key);
            if (fromEnv != null && !fromEnv.isBlank()) {
                values.put(key, fromEnv);
            }
        }
        return values;
    }
}
