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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LegacyMigrationTest {
    @Test
    @EnabledIfEnvironmentVariable(named = "RAYCHI_TEST_MYSQL_URL", matches = ".+")
    void mysqlMigrationPreservesCaseDistinctLegacyTags() {
        String url = System.getenv("RAYCHI_TEST_MYSQL_URL");
        String user = System.getenv("RAYCHI_TEST_MYSQL_USER");
        String password = System.getenv("RAYCHI_TEST_MYSQL_PASSWORD");
        var source = new DriverManagerDataSource(url, user, password);
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .target("1").load().migrate();

        JdbcTemplate db = new JdbcTemplate(source);
        String id = UUID.randomUUID().toString();
        Timestamp now = Timestamp.from(Instant.now());
        db.update("""
                INSERT INTO articles (id,slug,status,version,draft_title,draft_summary,draft_body,draft_tags,
                  public_title,public_summary,public_body,public_tags,created_at,updated_at,published_at,public_updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, "case-tags", "PUBLISHED", 1, "Case tags", "", "Draft", "[\"Tech\",\"tech\"]",
                "Case tags", "", "Published", "[\"Tech\",\"tech\"]", now, now, now, now);

        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .target("2").load().migrate();
        db.execute("ALTER TABLE tags MODIFY name VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL");
        db.update("INSERT INTO tags (name,created_at) VALUES (?,?)", "Tech", now);

        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .target("3").load().migrate();
        assertThat(db.queryForList("SELECT name FROM tags ORDER BY name", String.class))
                .containsExactlyInAnyOrder("Tech", "tech");

        ArticleService articles = new ArticleService(db, new ObjectMapper());
        var old = articles.getAdmin(id);
        assertThat(old.tags()).containsExactly("Tech", "tech");
        var saved = articles.save(id, new ArticleService.ArticleInput(old.version(), old.slug(), old.title(),
                old.summary(), old.bodyMarkdown(), List.of("Tech", "tech"), null));
        assertThat(saved.tags()).containsExactly("Tech", "tech");
        assertThat(articles.getPublic("case-tags").tags()).containsExactly("Tech", "tech");

        // An existing candidate database can have V3 applied with the old case-insensitive column.
        db.update("DELETE FROM tags WHERE name=?", "tech");
        db.update("UPDATE articles SET draft_tags=?, public_tags=? WHERE id=?", "[\"Tech\"]", "[\"Tech\"]", id);
        db.execute("ALTER TABLE tags MODIFY name VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL");
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .target("4").load().migrate();
        TaxonomyController taxonomy = new TaxonomyController(db);
        assertThat(taxonomy.createTag(new TaxonomyController.Name("tech")).name()).isEqualTo("tech");
        assertThat(taxonomy.createTag(new TaxonomyController.Name("TECH")).name()).isEqualTo("TECH");
        assertThat(db.queryForList("SELECT name FROM tags ORDER BY name", String.class))
                .containsExactlyInAnyOrder("Tech", "tech", "TECH");
    }

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
                .target("4").load().migrate();
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
