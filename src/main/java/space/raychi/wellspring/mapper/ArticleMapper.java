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
    private final boolean mysql;
    public ArticleMapper(JdbcTemplate db) {
        this.db = db;
        this.mysql = Boolean.TRUE.equals(db.execute((org.springframework.jdbc.core.ConnectionCallback<Boolean>) connection ->
                "MySQL".equals(connection.getMetaData().getDatabaseProductName())));
    }

    private record Filter(String where, List<Object> args) {}

    private static Filter adminFilter(String status, String type) {
        String where = " WHERE status<>'TRASHED'" + typeClause(type) +
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

    private Filter adminFilter(String status, String type, String category, String tag) {
        Filter base = adminFilter(status, type);
        String where = base.where();
        if (category != null && !category.isBlank()) {
            where += " AND content_type='ARTICLE' AND draft_category=?";
            base.args().add(category);
        }
        if (tag != null && !tag.isBlank()) {
            where += mysql ? " AND JSON_CONTAINS(draft_tags,?)=1" : " AND LOCATE(?,draft_tags)>0";
            try { base.args().add(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tag)); }
            catch (com.fasterxml.jackson.core.JsonProcessingException ex) { throw new IllegalArgumentException(ex); }
        }
        return new Filter(where, base.args());
    }

    public long countAdmin(String status, String type, String category, String tag) {
        Filter filter = adminFilter(status, type, category, tag);
        return db.queryForObject("SELECT COUNT(*) FROM articles" + filter.where(), Long.class, filter.args().toArray());
    }

    public List<ArticleEntity> selectAdmin(String status, String type, String category, String tag, String sort, int limit, int offset) {
        Filter filter = adminFilter(status, type, category, tag);
        String order = switch (sort) {
            case "oldest" -> "updated_at ASC,id ASC";
            case "type" -> "content_type ASC,updated_at DESC,id DESC";
            case "category" -> "draft_category ASC,updated_at DESC,id DESC";
            case "tag" -> "draft_tags ASC,updated_at DESC,id DESC";
            default -> "updated_at DESC,id DESC";
        };
        filter.args().add(limit); filter.args().add(offset);
        return db.query("SELECT * FROM articles" + filter.where() + " ORDER BY " + order + " LIMIT ? OFFSET ?", ArticleMapper::map, filter.args().toArray());
    }

    private static Filter publicFilter(String type, String category, String tag) {
        String where = " WHERE status='PUBLISHED'" + typeClause(type)
                + (category == null || category.isBlank() ? "" : " AND content_type='ARTICLE' AND public_category=?")
                + (tag == null || tag.isBlank() ? "" : " AND EXISTS (SELECT 1 FROM public_content_tags t WHERE t.article_id=articles.id AND t.name=?)");
        List<Object> args = new ArrayList<>();
        if (type != null && !type.equals("POST")) args.add(type);
        if (category != null && !category.isBlank()) args.add(category);
        if (tag != null && !tag.isBlank()) args.add(tag);
        return new Filter(where, args);
    }

    public long countPublished(String type, String category, String tag) {
        Filter filter = publicFilter(type, category, tag);
        return db.queryForObject("SELECT COUNT(*) FROM articles" + filter.where(), Long.class, filter.args().toArray());
    }

    public List<ArticleEntity> selectPublished(String type, String category, String tag, String sort, int limit, int offset) {
        Filter filter = publicFilter(type, category, tag);
        String order = switch (sort) {
            case "oldest" -> "published_at ASC,id ASC";
            case "title" -> "COALESCE(NULLIF(public_title,''),public_body) ASC,id ASC";
            default -> "published_at DESC,id DESC";
        };
        filter.args().add(limit); filter.args().add(offset);
        return db.query("SELECT * FROM articles" + filter.where() + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                ArticleMapper::map, filter.args().toArray());
    }

    public void replacePublishedTags(String id, List<String> tags) {
        db.update("DELETE FROM public_content_tags WHERE article_id=?", id);
        for (String tag : tags) db.update("INSERT INTO public_content_tags(article_id,name) VALUES (?,?)", id, tag);
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
    public void applyPublicationSummary(String id, String summary, boolean draftMatches) {
        db.update("UPDATE articles SET public_summary=?,draft_summary=CASE WHEN ? THEN ? ELSE draft_summary END,version=version+1 WHERE id=?",
                summary, draftMatches, summary, id);
    }
    public void trash(String id, Instant now) {
        db.update("UPDATE articles SET status='TRASHED',trashed_at=?,updated_at=?,version=version+1 WHERE id=?",
            Timestamp.from(now), Timestamp.from(now), id);
    }
    public void recover(String id, Instant now) {
        db.update("UPDATE articles SET status='DRAFT',trashed_at=NULL,updated_at=?,version=version+1 WHERE id=?",
            Timestamp.from(now), id);
    }
    public long countTrash() {
        return db.queryForObject("SELECT COUNT(*) FROM articles WHERE status='TRASHED'", Long.class);
    }
    public List<space.raychi.wellspring.entity.TrashItemEntity> selectTrash(int limit, int offset) {
        return db.query("""
            SELECT id,slug,content_type,draft_title,version,trashed_at,updated_at FROM articles
            WHERE status='TRASHED' ORDER BY trashed_at DESC,id DESC LIMIT ? OFFSET ?
            """, (rs, n) -> new space.raychi.wellspring.entity.TrashItemEntity(rs.getString("id"), rs.getString("slug"),
                rs.getString("content_type"), rs.getString("draft_title"), rs.getLong("version"),
                instant(rs, "trashed_at"), instant(rs, "updated_at")), limit, offset);
    }
    public Optional<space.raychi.wellspring.entity.TrashItemEntity> selectTrashItem(String id) {
        return db.query("""
            SELECT id,slug,content_type,draft_title,version,trashed_at,updated_at FROM articles
            WHERE status='TRASHED' AND id=?
            """, (rs, n) -> new space.raychi.wellspring.entity.TrashItemEntity(rs.getString("id"), rs.getString("slug"),
                rs.getString("content_type"), rs.getString("draft_title"), rs.getLong("version"),
                instant(rs, "trashed_at"), instant(rs, "updated_at")), id).stream().findFirst();
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
