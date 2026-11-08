package space.raychi.wellspring;

import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

/** Run existing application tests against separate schemas in an explicitly disposable PG database. */
public class PostgresTestInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    @Override
    public void initialize(ConfigurableApplicationContext context) {
        String url = System.getenv("RAYCHI_TEST_POSTGRES_URL");
        if (url == null || url.isBlank()) return;
        if (!url.matches("jdbc:postgresql://[^/]+/[a-zA-Z0-9_]+_test"))
            throw new IllegalArgumentException("PostgreSQL tests require an explicit _test database URL");
        String user = System.getenv("RAYCHI_TEST_POSTGRES_USER");
        String password = System.getenv("RAYCHI_TEST_POSTGRES_PASSWORD");
        String schema = "test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(url, user, password);
             var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        } catch (java.sql.SQLException error) {
            throw new IllegalStateException("Cannot create isolated PostgreSQL test schema", error);
        }
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("postgres-test", Map.of(
                "spring.datasource.url", url + "?currentSchema=" + schema,
                "spring.datasource.username", user, "spring.datasource.password", password,
                "spring.flyway.locations", "classpath:db/postgresql",
                "spring.datasource.hikari.maximum-pool-size", "3",
                "spring.datasource.hikari.minimum-idle", "0")));
    }
}
