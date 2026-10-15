package space.raychi.wellspring.reading;

import static space.raychi.wellspring.reading.ReadingModels.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReadingMapper {
    private final JdbcTemplate db;

    public ReadingMapper(JdbcTemplate db) { this.db = db; }

    public Settings settings() {
        return db.queryForObject(
                "SELECT version,display_days,translation_assistant_id FROM reading_settings WHERE id=1",
                (rs, n) -> new Settings(rs.getLong("version"), rs.getInt("display_days"),
                        rs.getString("translation_assistant_id")));
    }

    public int updateSettings(SettingsInput input, Instant now) {
        return db.update("UPDATE reading_settings SET translation_assistant_id=?,version=version+1,updated_at=? WHERE id=1 AND version=?",
                blankToNull(input.translationAssistantId()), Timestamp.from(now), input.version());
    }

    public List<Source> sources(boolean publicOnly) {
        return db.query("SELECT * FROM reading_sources" + (publicOnly ? " WHERE enabled=TRUE" : "")
                        + " ORDER BY position,id", (rs, n) -> new Source(
                rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("site_url"), rs.getString("listing_url"), rs.getString("adapter"),
                rs.getBoolean("enabled"), rs.getInt("position"), instant(rs.getTimestamp("last_checked_at")),
                instant(rs.getTimestamp("last_success_at")), rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()));
    }

    public Optional<Source> source(String id) {
        return db.query("SELECT * FROM reading_sources WHERE id=?", (rs, n) -> new Source(
                rs.getString("id"), rs.getString("name"), rs.getString("description"),
                rs.getString("site_url"), rs.getString("listing_url"), rs.getString("adapter"),
                rs.getBoolean("enabled"), rs.getInt("position"), instant(rs.getTimestamp("last_checked_at")),
                instant(rs.getTimestamp("last_success_at")), rs.getString("last_error"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), id)
                .stream().findFirst();
    }

    public void insertSource(Source value) {
        db.update("INSERT INTO reading_sources(id,name,description,site_url,listing_url,adapter,enabled,position,last_checked_at,last_success_at,last_error,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.name(), value.description(), value.siteUrl(), value.listingUrl(), value.adapter(),
                value.enabled(), value.position(), timestamp(value.lastCheckedAt()), timestamp(value.lastSuccessAt()),
                value.lastError(), Timestamp.from(value.createdAt()), Timestamp.from(value.updatedAt()));
    }

    public int updateSource(String id, SourceInput value, Instant now) {
        return db.update("UPDATE reading_sources SET name=?,description=?,site_url=?,listing_url=?,adapter=?,enabled=?,position=?,updated_at=? WHERE id=?",
                value.name(), value.description(), value.siteUrl(), value.listingUrl(), value.adapter(),
                value.enabled(), value.position(), Timestamp.from(now), id);
    }

    public int deleteSource(String id) { return db.update("DELETE FROM reading_sources WHERE id=?", id); }

    public void syncSuccess(String id, Instant now) {
        db.update("UPDATE reading_sources SET last_checked_at=?,last_success_at=?,last_error=NULL,updated_at=? WHERE id=?",
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), id);
    }

    public void syncFailure(String id, Instant now, String code) {
        db.update("UPDATE reading_sources SET last_checked_at=?,last_error=?,updated_at=? WHERE id=?",
                Timestamp.from(now), code, Timestamp.from(now), id);
    }

    public int articleCount(String sourceId) {
        Integer value = db.queryForObject("SELECT COUNT(*) FROM reading_articles WHERE source_id=?", Integer.class, sourceId);
        return value == null ? 0 : value;
    }

    public List<Article> publicArticles(String sourceId, Instant since) {
        return db.query("SELECT * FROM reading_articles WHERE source_id=? AND published_at>=? ORDER BY published_at DESC,id DESC",
                (rs, n) -> article(rs), sourceId, Timestamp.from(since));
    }

    public Optional<Article> byUrl(String url) {
        return db.query("SELECT * FROM reading_articles WHERE external_url_hash=? AND external_url=?", (rs, n) -> article(rs), sha256(url), url)
                .stream().findFirst();
    }

    public void insertArticle(Article value) {
        db.update("INSERT INTO reading_articles(id,source_id,external_url,external_url_hash,original_title,translated_title,published_at,discovered_at,updated_at,title_hash,translation_status,translation_task_id,translation_attempts,next_translation_at,translation_revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                value.id(), value.sourceId(), value.externalUrl(), sha256(value.externalUrl()), value.originalTitle(), value.translatedTitle(),
                Timestamp.from(value.publishedAt()), Timestamp.from(value.discoveredAt()), Timestamp.from(value.updatedAt()),
                value.titleHash(), value.translationStatus(), value.translationTaskId(), value.translationAttempts(),
                timestamp(value.nextTranslationAt()), value.translationRevision());
    }

    public void refreshArticle(Article previous, String sourceId, String title, Instant publishedAt,
                               String titleHash, Instant now) {
        boolean titleChanged = !previous.titleHash().equals(titleHash);
        db.update("UPDATE reading_articles SET source_id=?,original_title=?,published_at=?,updated_at=?,title_hash=?,translated_title=?,translation_status=?,translation_task_id=?,translation_attempts=?,next_translation_at=? WHERE id=?",
                sourceId, title, Timestamp.from(publishedAt), Timestamp.from(now), titleHash,
                titleChanged ? null : previous.translatedTitle(),
                titleChanged ? "PENDING" : previous.translationStatus(),
                titleChanged ? null : previous.translationTaskId(),
                titleChanged ? 0 : previous.translationAttempts(),
                titleChanged ? Timestamp.from(now) : timestamp(previous.nextTranslationAt()), previous.id());
    }

    public Optional<Article> nextTranslation(Instant now) {
        return db.query("SELECT * FROM reading_articles WHERE translation_status IN ('PENDING','FAILED') AND (next_translation_at IS NULL OR next_translation_at<=?) ORDER BY discovered_at,id LIMIT 1",
                (rs, n) -> article(rs), Timestamp.from(now)).stream().findFirst();
    }

    public Optional<Article> runningTranslation() {
        return db.query("SELECT * FROM reading_articles WHERE translation_status='RUNNING' ORDER BY updated_at,id LIMIT 1",
                (rs, n) -> article(rs)).stream().findFirst();
    }

    public void translationRunning(String id, String taskId, Instant now) {
        db.update("UPDATE reading_articles SET translation_status='RUNNING',translation_task_id=?,updated_at=? WHERE id=?",
                taskId, Timestamp.from(now), id);
    }

    public void translationSucceeded(String id, String title, Instant now) {
        db.update("UPDATE reading_articles SET translated_title=?,translation_status='SUCCEEDED',translation_task_id=NULL,next_translation_at=NULL,translation_revision=translation_revision+1,updated_at=? WHERE id=?",
                title, Timestamp.from(now), id);
    }

    public void translationFailed(String id, Instant retryAt, Instant now) {
        db.update("UPDATE reading_articles SET translation_status='FAILED',translation_task_id=NULL,translation_attempts=translation_attempts+1,next_translation_at=?,updated_at=? WHERE id=?",
                Timestamp.from(retryAt), Timestamp.from(now), id);
    }

    private static Article article(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Article(rs.getString("id"), rs.getString("source_id"), rs.getString("external_url"),
                rs.getString("original_title"), rs.getString("translated_title"),
                rs.getTimestamp("published_at").toInstant(), rs.getTimestamp("discovered_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("title_hash"),
                rs.getString("translation_status"), rs.getString("translation_task_id"),
                rs.getInt("translation_attempts"), instant(rs.getTimestamp("next_translation_at")),
                rs.getInt("translation_revision"));
    }

    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
