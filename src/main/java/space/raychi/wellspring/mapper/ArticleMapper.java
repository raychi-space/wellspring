package space.raychi.wellspring.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import space.raychi.wellspring.entity.ArticleDraftEntity;
import space.raychi.wellspring.entity.ArticleEntity;

@Repository
public class ArticleMapper {
    private final JdbcTemplate db;
    public ArticleMapper(JdbcTemplate db) { this.db = db; }

    private record Filter(String where, List<Object> args) {}

    private static Filter adminFilter(String status, String type) {
        String where = " WHERE 1=1" + typeClause(type) +
                (status == null || status.isBlank() ? "" : " AND status=?");
        List<Object> args = new ArrayList<>();
        if (type != null && !type.equals("POST")) args.add(type);
        if (status != null && !status.isBlank()) args.add(status);
        return new Filter(where, args);
    }

    private static String typeClause(String type) {
        return type == null ? "" : type.equals("POST")
                ? " AND content_type IN ('POST','THOUGHT')" : " AND content_type=?";
    }

    public long countAdmin(String status, String type) {
        Filter filter = adminFilter(status, type);
        return db.queryForObject("SELECT COUNT(*) FROM articles" + filter.where(), Long.class, filter.args().toArray());
    }

    public List<ArticleEntity> selectAdmin(String status, String type, int limit, int offset) {
        Filter filter = adminFilter(status, type);
        filter.args().add(limit);
        filter.args().add(offset);
        return db.query("SELECT * FROM articles" + filter.where() +
                " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?", ArticleMapper::map, filter.args().toArray());
    }

    public List<ArticleEntity> selectPublished(String type, String category) {
        String where = " WHERE status='PUBLISHED'" + typeClause(type) +
                (category == null || category.isBlank() ? "" : " AND content_type='ARTICLE' AND public_category=?");
        List<Object> args = new ArrayList<>();
        if (type != null && !type.equals("POST")) args.add(type);
        if (category != null && !category.isBlank()) args.add(category);
        return db.query("SELECT * FROM articles" + where + " ORDER BY published_at DESC, id DESC",
                ArticleMapper::map, args.toArray());
    }

    public Optional<ArticleEntity> selectPublishedBySlug(String type, String slug) {
        List<ArticleEntity> rows = db.query("SELECT * FROM articles WHERE status='PUBLISHED'" + typeClause(type) + " AND slug=?",
                ArticleMapper::map, type.equals("POST") ? new Object[]{slug} : new Object[]{type, slug});
        return rows.stream().findFirst();
    }

    public Optional<ArticleEntity> selectById(String id, boolean lock) {
        return db.query("SELECT * FROM articles WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                ArticleMapper::map, id).stream().findFirst();
    }

    public void insertDraft(ArticleDraftEntity value) {
        db.update("""
            INSERT INTO articles (id,slug,content_type,status,version,draft_title,draft_summary,draft_body,draft_tags,draft_cover,draft_category,created_at,updated_at)
            VALUES (?,?,?, 'DRAFT', 0,?,?,?,?,?,?,?,?)
            """, value.id(), value.slug(), value.type(), value.title(), value.summary(), value.body(), value.tags(),
                value.cover(), value.category(), Timestamp.from(value.updatedAt()), Timestamp.from(value.updatedAt()));
    }

    public void updateDraft(ArticleDraftEntity value) {
        db.update("""
            UPDATE articles SET content_type=?,slug=?,draft_title=?,draft_summary=?,draft_body=?,draft_tags=?,draft_cover=?,draft_category=?,public_category=?,
            version=version+1,updated_at=? WHERE id=?
            """, value.type(), value.slug(), value.title(), value.summary(), value.body(), value.tags(), value.cover(),
                value.category(), value.publicCategory(), Timestamp.from(value.updatedAt()), value.id());
    }

    public void publish(String id, String type, String category, Instant now) {
        db.update("""
            UPDATE articles SET content_type=?,draft_category=?,status='PUBLISHED', public_title=draft_title, public_summary=draft_summary,
            public_body=draft_body, public_tags=draft_tags, public_cover=draft_cover, public_category=?,
            published_at=COALESCE(published_at, ?), public_updated_at=?, version=version+1, updated_at=?
            WHERE id=?
            """, type, category, category, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), id);
    }

    public void unpublish(String id, Instant now) {
        db.update("UPDATE articles SET status='DRAFT',version=version+1,updated_at=? WHERE id=?", Timestamp.from(now), id);
    }
    public void delete(String id) {
        db.update("DELETE FROM articles WHERE id=?", id);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static ArticleEntity map(ResultSet rs, int rowNum) throws SQLException {
        return new ArticleEntity(rs.getString("id"), rs.getString("slug"), rs.getString("content_type"), rs.getString("status"), rs.getLong("version"),
                rs.getString("draft_title"), rs.getString("draft_summary"), rs.getString("draft_body"),
                rs.getString("draft_tags"), rs.getString("draft_cover"), rs.getString("draft_category"), rs.getString("public_title"),
                rs.getString("public_summary"), rs.getString("public_body"), rs.getString("public_tags"),
                rs.getString("public_cover"), rs.getString("public_category"), instant(rs, "created_at"), instant(rs, "updated_at"),
                instant(rs, "published_at"), instant(rs, "public_updated_at"));
    }

}
