package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class LegacyMigrationTest {
    @Test
    void existingPublishedArticleKeepsSlugSnapshotAndDefaultCategory() throws Exception {
        String url = "jdbc:h2:mem:legacy_" + UUID.randomUUID().toString().replace("-", "") +
                ";MODE=MySQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("1").load().migrate();
        String id = UUID.randomUUID().toString();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var insert = connection.prepareStatement("""
                 INSERT INTO articles (id,slug,status,version,draft_title,draft_summary,draft_body,draft_tags,
                   public_title,public_summary,public_body,public_tags,created_at,updated_at,published_at,public_updated_at)
                 VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                 """)) {
            Object[] values = { id, "old-link", "PUBLISHED", 4, "旧标题", "旧摘要", "工作稿", "[]",
                    "旧标题", "旧摘要", "公开正文", "[]", Timestamp.from(Instant.now()),
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()) };
            for (int i = 0; i < values.length; i++) insert.setObject(i + 1, values[i]);
            insert.executeUpdate();
        }
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("2").load().migrate();
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var query = connection.prepareStatement("SELECT slug,content_type,public_body,draft_category,public_category FROM articles WHERE id=?")) {
            query.setString(1, id);
            try (var row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString("slug")).isEqualTo("old-link");
                assertThat(row.getString("content_type")).isEqualTo("ARTICLE");
                assertThat(row.getString("public_body")).isEqualTo("公开正文");
                assertThat(row.getString("draft_category")).isEqualTo("未分类");
                assertThat(row.getString("public_category")).isEqualTo("未分类");
            }
        }
    }
}
