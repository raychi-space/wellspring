package space.raychi.wellspring.article;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LegacyMigrationTest {
    @Test
    void existingPublishedArticleKeepsSlugSnapshotCategoryAndEditableTags() throws Exception {
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
            Object[] values = { id, "old-link", "PUBLISHED", 4, "旧标题", "旧摘要", "工作稿", "[\"旧标签\"]",
                    "旧标题", "旧摘要", "公开正文", "[\"旧标签\",\"公开旧标签\"]", Timestamp.from(Instant.now()),
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()) };
            for (int i = 0; i < values.length; i++) insert.setObject(i + 1, values[i]);
            insert.executeUpdate();
        }
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .target("3").load().migrate();
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

        JdbcTemplate db = new JdbcTemplate(new DriverManagerDataSource(url, "sa", ""));
        assertThat(db.queryForList("SELECT name FROM tags ORDER BY name", String.class))
                .containsExactlyInAnyOrder("旧标签", "公开旧标签");
        ArticleService articles = new ArticleService(db, new ObjectMapper());
        var old = articles.getAdmin(id);
        assertThat(old.tags()).containsExactly("旧标签");
        var cleared = articles.save(id, new ArticleService.ArticleInput(old.version(), old.slug(), old.title(),
                old.summary(), old.bodyMarkdown(), List.of(), null));
        var restored = articles.save(id, new ArticleService.ArticleInput(cleared.version(), cleared.slug(),
                cleared.title(), cleared.summary(), cleared.bodyMarkdown(), List.of("旧标签", "公开旧标签"), null));
        assertThat(restored.tags()).containsExactly("旧标签", "公开旧标签");
        assertThat(articles.getPublic("old-link").tags()).containsExactly("旧标签", "公开旧标签");
    }
}
