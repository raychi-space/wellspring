package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import space.raychi.wellspring.mapper.ArticleMapper;
import space.raychi.wellspring.tools.DatabaseTransfer;

@EnabledIfEnvironmentVariable(named = "RAYCHI_MIGRATION_TEST_MYSQL_URL", matches = ".+_test.*")
class DatabaseTransferTest {
    @Test
    void copiesAllColumnsRejectsOverwriteAndDetectsTampering() throws Exception {
        Map<String, String> env = fixture();
        DatabaseTransfer.run(true, env);
        DatabaseTransfer.run(false, env);
        var target = target(env);
        assertThat(target.queryForObject("SELECT draft_body FROM articles", String.class))
                .isEqualTo("工作稿😀\n'quoted'\\path");
        assertThat(target.queryForObject("SELECT published_at FROM articles", Timestamp.class).toInstant())
                .isEqualTo(Instant.parse("2026-11-09T00:01:02.123456Z"));
        assertThat(target.queryForObject("SELECT credential_version FROM admin_account", Long.class)).isEqualTo(7);
        assertThat(target.queryForObject("SELECT count(*) FROM tags", Long.class)).isEqualTo(2);
        assertThat(target.queryForObject("SELECT count(*) FROM categories WHERE name='tech'", Long.class)).isEqualTo(1);
        var mapper = new ArticleMapper(target);
        assertThat(mapper.countAdmin(null, null, null, "Tech")).isEqualTo(1);
        assertThat(mapper.countAdmin(null, null, null, "tech")).isZero();
        assertThat(mapper.countAdmin(null, null, null, "quoted\"slash\\")).isEqualTo(1);
        assertThatThrownBy(() -> DatabaseTransfer.run(true, env)).isInstanceOf(IllegalStateException.class);
        target.update("UPDATE articles SET draft_body='tampered'");
        assertThatThrownBy(() -> DatabaseTransfer.run(false, env)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unrepresentableTextRollsBackEveryCopiedTableAndNeverChangesSource() throws Exception {
        Map<String, String> env = fixture();
        var source = new JdbcTemplate(new DriverManagerDataSource(env.get("RAYCHI_MIGRATION_SOURCE_URL"),
                env.get("RAYCHI_MIGRATION_SOURCE_USER"), env.get("RAYCHI_MIGRATION_SOURCE_PASSWORD")));
        source.update("UPDATE articles SET draft_body=?", "contains\u0000nul");
        assertThatThrownBy(() -> DatabaseTransfer.run(true, env)).isInstanceOf(java.sql.SQLException.class);
        assertThat(target(env).queryForObject("SELECT count(*) FROM admin_account", Long.class)).isZero();
        assertThat(source.queryForObject("SELECT draft_body FROM articles", String.class)).isEqualTo("contains\u0000nul");
    }

    private Map<String, String> fixture() throws Exception {
        String mysql = System.getenv("RAYCHI_MIGRATION_TEST_MYSQL_URL");
        String user = System.getenv("RAYCHI_MIGRATION_TEST_MYSQL_USER"), password = System.getenv("RAYCHI_MIGRATION_TEST_MYSQL_PASSWORD");
        try (var connection = DriverManager.getConnection(mysql, user, password);
             var row = connection.createStatement().executeQuery("SELECT DATABASE()")) {
            row.next(); assertThat(row.getString(1)).endsWith("_test");
        }
        var flyway = Flyway.configure().dataSource(mysql, user, password).locations("classpath:db/migration")
                .cleanDisabled(false).load();
        flyway.clean(); flyway.migrate();
        var source = new JdbcTemplate(new DriverManagerDataSource(mysql, user, password));
        source.update("INSERT INTO admin_account VALUES (1,'fixture-admin','fixture-hash-kept-byte-for-byte',7)");
        source.update("INSERT INTO categories VALUES ('Téch',CURRENT_TIMESTAMP)");
        source.update("INSERT INTO tags VALUES ('Tech',CURRENT_TIMESTAMP),('tech',CURRENT_TIMESTAMP)");
        var at = Timestamp.from(Instant.parse("2026-11-09T00:01:02.123456Z"));
        source.update("""
            INSERT INTO articles(id,slug,status,version,draft_title,draft_summary,draft_body,draft_tags,
              public_title,public_summary,public_body,public_tags,created_at,updated_at,published_at,public_updated_at)
            VALUES (?,?,?,7,?,?,?,?,?,?,?,?,?,?,?,?)
            """, UUID.randomUUID().toString(), "transfer-fixture", "PUBLISHED", "迁移", "", "工作稿😀\n'quoted'\\path",
                "[\"Tech\",\"quoted\\\"slash\\\\\"]", "公开标题", "公开摘要", "公开正文", "[\"Tech\",\"tech\"]", at, at, at, at);
        String pg = System.getenv("RAYCHI_TEST_POSTGRES_URL");
        if (pg == null || !pg.matches("jdbc:postgresql://[^/]+/[a-zA-Z0-9_]+_test"))
            throw new IllegalArgumentException("Transfer tests require a disposable PostgreSQL _test database");
        String schema = "transfer_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(pg, System.getenv("RAYCHI_TEST_POSTGRES_USER"),
                System.getenv("RAYCHI_TEST_POSTGRES_PASSWORD"))) {
            connection.createStatement().execute("CREATE SCHEMA " + schema);
        }
        var env = new HashMap<String, String>();
        env.put("RAYCHI_MIGRATION_SOURCE_URL", mysql); env.put("RAYCHI_MIGRATION_SOURCE_USER", user);
        env.put("RAYCHI_MIGRATION_SOURCE_PASSWORD", password);
        env.put("RAYCHI_MIGRATION_TARGET_URL", pg + "?currentSchema=" + schema);
        env.put("RAYCHI_MIGRATION_TARGET_USER", System.getenv("RAYCHI_TEST_POSTGRES_USER"));
        env.put("RAYCHI_MIGRATION_TARGET_PASSWORD", System.getenv("RAYCHI_TEST_POSTGRES_PASSWORD"));
        env.put("RAYCHI_MIGRATION_TARGET_CONFIRM", pg.substring(pg.lastIndexOf('/') + 1));
        return env;
    }

    private JdbcTemplate target(Map<String, String> env) {
        return new JdbcTemplate(new DriverManagerDataSource(env.get("RAYCHI_MIGRATION_TARGET_URL"),
                env.get("RAYCHI_MIGRATION_TARGET_USER"), env.get("RAYCHI_MIGRATION_TARGET_PASSWORD")));
    }
}
