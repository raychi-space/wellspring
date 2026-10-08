package space.raychi.wellspring.mapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import space.raychi.wellspring.entity.ContentRevisionEntity;
import space.raychi.wellspring.entity.ContentRevisionSummaryEntity;

@Repository
public class ContentRevisionMapper {
    private final JdbcTemplate db;
    public ContentRevisionMapper(JdbcTemplate db) { this.db = db; }
    public long count(String articleId) {
        return db.queryForObject("SELECT COUNT(*) FROM content_revisions WHERE article_id=?", Long.class, articleId);
    }
    public void insert(ContentRevisionEntity revision) {
        db.update("""
            INSERT INTO content_revisions (id,article_id,article_version,operation,title,summary,body_markdown,tags_json,category,cover_url,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """, revision.id(), revision.articleId(), revision.articleVersion(), revision.operation(), revision.title(), revision.summary(),
            revision.bodyMarkdown(), revision.tagsJson(), revision.category(), revision.coverUrl(), Timestamp.from(revision.createdAt()));
        db.update("""
            DELETE FROM content_revisions WHERE article_id=? AND id NOT IN (
                SELECT kept.id FROM (SELECT id FROM content_revisions WHERE article_id=? ORDER BY article_version DESC LIMIT 100) kept)
            """, revision.articleId(), revision.articleId());
    }
    public List<ContentRevisionSummaryEntity> list(String articleId, int limit, int offset) {
        return db.query("""
            SELECT id,article_version,operation,title,summary,created_at FROM content_revisions
            WHERE article_id=? ORDER BY article_version DESC LIMIT ? OFFSET ?
            """, (rs, n) -> new ContentRevisionSummaryEntity(rs.getString("id"), rs.getLong("article_version"),
                rs.getString("operation"), rs.getString("title"), rs.getString("summary"), rs.getTimestamp("created_at").toInstant()),
            articleId, limit, offset);
    }
    public Optional<ContentRevisionEntity> get(String articleId, String revisionId) {
        return db.query("SELECT * FROM content_revisions WHERE article_id=? AND id=?", (rs, n) ->
            new ContentRevisionEntity(rs.getString("id"), rs.getString("article_id"), rs.getLong("article_version"),
                rs.getString("operation"), rs.getString("title"), rs.getString("summary"), rs.getString("body_markdown"),
                rs.getString("tags_json"), rs.getString("category"), rs.getString("cover_url"), rs.getTimestamp("created_at").toInstant()),
            articleId, revisionId).stream().findFirst();
    }
}
