package space.raychi.wellspring.config;

import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DatabaseConfiguration {
    @Bean
    FlywayConfigurationCustomizer databaseMigrations(DataSource dataSource) {
        return configuration -> {
            try (var connection = dataSource.getConnection()) {
                if ("PostgreSQL".equals(connection.getMetaData().getDatabaseProductName()))
                    configuration.locations("classpath:db/postgresql");
            } catch (java.sql.SQLException error) {
                throw new IllegalStateException("Cannot select database migrations", error);
            }
        };
    }
}
