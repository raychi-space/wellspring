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
