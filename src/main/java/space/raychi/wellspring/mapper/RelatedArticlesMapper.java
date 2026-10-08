package space.raychi.wellspring.mapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import space.raychi.wellspring.entity.RelatedArticleEntity;

@Repository
public class RelatedArticlesMapper {
    private final JdbcTemplate db;
    public RelatedArticlesMapper(JdbcTemplate db) { this.db = db; }

    public Optional<RelatedArticleEntity> target(String slug) {
        return db.query("""
            SELECT id,slug,public_title,public_summary,public_category,published_at
            FROM articles WHERE status='PUBLISHED' AND content_type='ARTICLE' AND slug=?
            """, RelatedArticlesMapper::map, slug).stream().findFirst();
    }

    public List<RelatedArticleEntity> candidates(String targetId, String category, int limit) {
        return db.query("""
            SELECT a.id,a.slug,a.public_title,a.public_summary,a.public_category,a.published_at,
                   COUNT(t.name)*3 + CASE WHEN a.public_category=? AND ?<>'' THEN 1 ELSE 0 END AS relevance
            FROM articles a
            LEFT JOIN public_content_tags t ON t.article_id=a.id
                AND t.name IN (SELECT own.name FROM public_content_tags own WHERE own.article_id=?)
            WHERE a.status='PUBLISHED' AND a.content_type='ARTICLE' AND a.id<>?
            GROUP BY a.id,a.slug,a.public_title,a.public_summary,a.public_category,a.published_at
            HAVING COUNT(t.name)>0 OR (a.public_category=? AND ?<>'')
            ORDER BY relevance DESC,a.published_at DESC,a.id DESC LIMIT ?
            """, RelatedArticlesMapper::map, category, category, targetId, targetId, category, category, limit);
    }

    private static RelatedArticleEntity map(ResultSet row, int index) throws SQLException {
        return new RelatedArticleEntity(row.getString("id"), row.getString("slug"),
                row.getString("public_title"), row.getString("public_summary"),
                row.getString("public_category"), row.getTimestamp("published_at").toInstant());
    }
}
