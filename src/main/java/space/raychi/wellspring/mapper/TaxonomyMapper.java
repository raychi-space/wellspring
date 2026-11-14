package space.raychi.wellspring.mapper;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import space.raychi.wellspring.entity.TaxonomyEntity;

@Repository
public class TaxonomyMapper {
    public enum Kind {
        CATEGORY("categories"), TAG("tags");
        private final String table;
        Kind(String table) { this.table = table; }
    }
    private final JdbcTemplate db;
    private final boolean postgres;
    public TaxonomyMapper(JdbcTemplate db) {
        this.db = db;
        this.postgres = Boolean.TRUE.equals(db.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) c ->
                "PostgreSQL".equals(c.getMetaData().getDatabaseProductName())));
    }

    /** The permanent default category is the guard for catalog/reference changes. */
    public void lock() {
        db.queryForObject("SELECT name FROM categories WHERE name='未分类' FOR UPDATE", String.class);
    }

    public int rename(Kind kind, String name, String next) {
        return db.update("UPDATE " + kind.table + " SET name=? WHERE name=?", next, name);
    }

    public int delete(Kind kind, String name) {
        return db.update("DELETE FROM " + kind.table + " WHERE name=?", name);
    }

    public record ReferenceRow(String id, String title, String status, String category, String tags, String scope) {}
    public List<ReferenceRow> referenceRows() {
        return db.query("""
            SELECT id,draft_title AS title,status,draft_category AS category,draft_tags AS tags,'draft' AS scope FROM articles
            UNION ALL
            SELECT id,draft_title,status,public_category,public_tags,'public' FROM articles WHERE public_updated_at IS NOT NULL
            UNION ALL
            SELECT a.id,a.draft_title,a.status,r.category,r.tags_json,'history' FROM content_revisions r JOIN articles a ON a.id=r.article_id
            """, (rs, n) -> new ReferenceRow(rs.getString("id"), rs.getString("title"), rs.getString("status"),
                    rs.getString("category"), rs.getString("tags"), rs.getString("scope")));
    }

    public List<TaxonomyEntity> selectAll(Kind kind) {
        return db.query("SELECT name,created_at FROM " + kind.table + " ORDER BY name",
                (rs, n) -> new TaxonomyEntity(rs.getString("name"), rs.getTimestamp("created_at").toInstant()));
    }

    public boolean exists(Kind kind, String name) {
        Integer count = db.queryForObject("SELECT COUNT(*) FROM " + kind.table + " WHERE name=?", Integer.class, name);
        return count != null && count > 0;
    }

    public void insert(Kind kind, TaxonomyEntity value) {
        db.update("INSERT INTO " + kind.table + " (name,created_at) VALUES (?,?)",
                value.name(), Timestamp.from(value.createdAt()));
    }

    public void insertIfAbsent(Kind kind, TaxonomyEntity value) {
        if (postgres) {
            // A caught unique violation aborts the whole PG transaction; avoid raising it.
            db.update("INSERT INTO " + kind.table + " (name,created_at) VALUES (?,?) ON CONFLICT (name) DO NOTHING",
                    value.name(), Timestamp.from(value.createdAt()));
        } else {
            try { insert(kind, value); }
            catch (org.springframework.dao.DuplicateKeyException alreadyCreated) { /* Concurrent publication. */ }
        }
    }
}
