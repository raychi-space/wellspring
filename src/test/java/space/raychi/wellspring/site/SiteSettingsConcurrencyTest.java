package space.raychi.wellspring.site;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.site.SiteSettingsController.Settings;

class SiteSettingsConcurrencyTest {
    @Test
    void concurrentSavesOfTheSameVersionAllowOnlyOneWinner() throws Exception {
        String url = "jdbc:h2:mem:settings_" + UUID.randomUUID().toString().replace("-", "") +
                ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration").load().migrate();

        CyclicBarrier bothReadyToUpdate = new CyclicBarrier(2);
        JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.startsWith("UPDATE site_settings ")) {
                    try {
                        bothReadyToUpdate.await(10, TimeUnit.SECONDS);
                    } catch (Exception ex) {
                        throw new IllegalStateException("Both saves must reach the update", ex);
                    }
                }
                return super.update(sql, args);
            }
        };
        SiteSettingsController settings = new SiteSettingsController(db, new ObjectMapper());
        Settings current = settings.adminSettings();
        Settings first = withSiteName(current, "First save");
        Settings second = withSiteName(current, "Second save");

        var workers = Executors.newFixedThreadPool(2);
        try {
            var firstResult = workers.submit(() -> saveOrConflict(settings, first));
            var secondResult = workers.submit(() -> saveOrConflict(settings, second));
            List<Object> results = List.of(firstResult.get(15, TimeUnit.SECONDS),
                    secondResult.get(15, TimeUnit.SECONDS));
            assertThat(results.stream().filter(Settings.class::isInstance).count()).isEqualTo(1);
            assertThat(results.stream().filter(ApiException.class::isInstance).count()).isEqualTo(1);
            ApiException conflict = (ApiException) results.stream().filter(ApiException.class::isInstance)
                    .findFirst().orElseThrow();
            assertThat(conflict.status()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(conflict.code()).isEqualTo("SETTINGS_VERSION_CONFLICT");

            Settings saved = settings.publicSettings();
            assertThat(saved.version()).isEqualTo(current.version() + 1);
            assertThat(saved.siteName()).isIn("First save", "Second save");
        } finally {
            workers.shutdownNow();
        }
    }

    private static Settings withSiteName(Settings value, String name) {
        return new Settings(value.version(), name, value.intro(), value.avatarUrl(), value.contacts(),
                value.accounts(), value.navigation(), value.homeSections());
    }

    private static Object saveOrConflict(SiteSettingsController settings, Settings value) {
        try {
            return settings.save(value);
        } catch (ApiException conflict) {
            return conflict;
        }
    }
}
