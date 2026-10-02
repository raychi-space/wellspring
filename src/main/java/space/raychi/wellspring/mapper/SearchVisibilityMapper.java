package space.raychi.wellspring.mapper;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SearchVisibilityMapper {
    private final JdbcTemplate db;
    public SearchVisibilityMapper(JdbcTemplate db) { this.db = db; }
    public List<Map<String, Object>> findPublished(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        String placeholders = ids.stream().map(id -> "?").collect(Collectors.joining(","));
        return db.queryForList("SELECT a.id,a.slug,a.content_type,a.public_title,CASE WHEN a.content_type<>'ARTICLE' THEN a.public_body ELSE NULL END AS generated_title_body,s.version FROM articles a JOIN search_sync_state s ON s.document_id=CONCAT('content:',a.id) WHERE a.status='PUBLISHED' AND s.desired_action='UPSERT' AND s.document_id IN (" + placeholders + ")", ids.toArray());
    }
}
