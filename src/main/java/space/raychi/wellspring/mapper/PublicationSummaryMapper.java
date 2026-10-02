package space.raychi.wellspring.mapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import space.raychi.wellspring.entity.PublicationSummary;

@Repository
public class PublicationSummaryMapper {
    private final JdbcTemplate db;
    public PublicationSummaryMapper(JdbcTemplate db) { this.db = db; }

    public Optional<PublicationSummary> get(String id) {
        return db.query("SELECT * FROM publication_summaries WHERE article_id=?", (r, n) ->
                new PublicationSummary(r.getString("article_id"), r.getString("job_id"), r.getString("assistant_id"),
                        r.getTimestamp("publication_at").toInstant(), r.getString("status"), r.getString("task_id"),
                        r.getTimestamp("started_at") == null ? null : r.getTimestamp("started_at").toInstant(),
                        r.getString("error_code")), id).stream().findFirst();
    }

    public List<String> due() {
        return db.queryForList("SELECT article_id FROM publication_summaries WHERE status IN ('PENDING','RUNNING') ORDER BY publication_at LIMIT 4", String.class);
    }

    public void enqueue(String id, String job, String assistant, Instant publishedAt) {
        delete(id);
        db.update("INSERT INTO publication_summaries(article_id,job_id,assistant_id,publication_at,status) VALUES (?,?,?,?,'PENDING')",
                id, job, assistant, Timestamp.from(publishedAt));
    }
    public void choose(String job, String assistant) {
        db.update("UPDATE publication_summaries SET assistant_id=? WHERE job_id=? AND status='PENDING'", assistant, job);
    }
    public void running(String job, String task) {
        db.update("UPDATE publication_summaries SET status='RUNNING',task_id=?,started_at=? WHERE job_id=? AND status='PENDING'",
                task, Timestamp.from(Instant.now()), job);
    }
    public void finish(String job, String status, String error) {
        db.update("UPDATE publication_summaries SET status=?,error_code=? WHERE job_id=? AND status IN ('PENDING','RUNNING')", status, error, job);
    }
    public void cancel(String id) {
        db.update("UPDATE publication_summaries SET status='CANCELLED',error_code=NULL WHERE article_id=? AND status IN ('PENDING','RUNNING')", id);
    }
    public void delete(String id) { db.update("DELETE FROM publication_summaries WHERE article_id=?", id); }
}
