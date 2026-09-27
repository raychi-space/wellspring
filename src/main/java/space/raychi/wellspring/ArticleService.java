package space.raychi.wellspring;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Image;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ArticleService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern ASSET_URL = Pattern.compile("^/api/v1/public/assets/([0-9a-fA-F-]{36})/content$");
    private static final Parser MARKDOWN = Parser.builder().extensions(List.of(TablesExtension.create())).build();
    private final JdbcTemplate db;
    private final ObjectMapper json;

    ArticleService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record ArticleInput(Long version, String slug, String title, String summary,
                               String bodyMarkdown, List<String> tags, String coverUrl) {}
    public record VersionInput(Long expectedVersion) {}
    public record AdminArticle(String id, String slug, String status, long version,
                               String title, String summary, String bodyMarkdown, List<String> tags,
                               String coverUrl, boolean hasUnpublishedChanges,
                               Instant createdAt, Instant updatedAt, Instant publishedAt, Instant publicUpdatedAt) {}
    public record PublicArticle(String id, String slug, String title, String summary,
                                String bodyMarkdown, List<String> tags, String coverUrl,
                                Instant publishedAt, Instant publicUpdatedAt) {}
    public record Page<T>(List<T> items, int page, int pageSize, long total) {}

    private record Row(String id, String slug, String status, long version,
                       String draftTitle, String draftSummary, String draftBody, String draftTags, String draftCover,
                       String publicTitle, String publicSummary, String publicBody, String publicTags, String publicCover,
                       Instant createdAt, Instant updatedAt, Instant publishedAt, Instant publicUpdatedAt) {}

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getString("id"), rs.getString("slug"), rs.getString("status"), rs.getLong("version"),
                rs.getString("draft_title"), rs.getString("draft_summary"), rs.getString("draft_body"),
                rs.getString("draft_tags"), rs.getString("draft_cover"), rs.getString("public_title"),
                rs.getString("public_summary"), rs.getString("public_body"), rs.getString("public_tags"),
                rs.getString("public_cover"), instant(rs, "created_at"), instant(rs, "updated_at"),
                instant(rs, "published_at"), instant(rs, "public_updated_at"));
    }

    private List<String> tags(String stored) {
        if (stored == null || stored.isBlank()) return List.of();
        try { return json.readValue(stored, new TypeReference<List<String>>() {}); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid stored tags", ex); }
    }

    private String tagsJson(List<String> value) {
        List<String> cleaned = value == null ? List.of() : value.stream()
                .filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().toList();
        if (cleaned.size() > 20) throw bad("标签不能超过 20 个。");
        if (cleaned.stream().anyMatch(s -> s.length() > 40))
            throw bad("标签不能超过 40 个字符。");
        try { return json.writeValueAsString(cleaned); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    private AdminArticle admin(Row r) {
        boolean changed = r.publicUpdatedAt() == null ||
                !Objects.equals(r.draftTitle(), r.publicTitle()) ||
                !Objects.equals(r.draftSummary(), r.publicSummary()) ||
                !Objects.equals(r.draftBody(), r.publicBody()) ||
                !Objects.equals(r.draftTags(), r.publicTags()) ||
                !Objects.equals(r.draftCover(), r.publicCover());
        return new AdminArticle(r.id(), r.slug(), r.status(), r.version(), r.draftTitle(), r.draftSummary(),
                r.draftBody(), tags(r.draftTags()), r.draftCover(), changed,
                r.createdAt(), r.updatedAt(), r.publishedAt(), r.publicUpdatedAt());
    }

    private PublicArticle published(Row r) {
        return new PublicArticle(r.id(), r.slug(), r.publicTitle(), r.publicSummary(), r.publicBody(),
                tags(r.publicTags()), r.publicCover(), r.publishedAt(), r.publicUpdatedAt());
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private static void validateSlug(String slug) {
        if (slug == null || slug.length() > 120 || !SLUG.matcher(slug).matches())
            throw bad("地址别名只能包含小写字母、数字和单个连字符，长度不超过 120。");
    }

    private static String shortValue(String value, int max, String label) {
        String result = value == null ? "" : value.trim();
        if (result.length() > max) throw bad(label + "过长。");
        return result;
    }

    private static String body(String value) {
        String result = value == null ? "" : value;
        if (result.length() > 1_000_000) throw bad("正文过长。");
        return result;
    }

    @Transactional
    public AdminArticle create(ArticleInput input) {
        String id = UUID.randomUUID().toString();
        String slug = input == null || input.slug() == null || input.slug().isBlank()
                ? "draft-" + id : input.slug().trim();
        validateSlug(slug);
        String title = input == null ? "" : shortValue(input.title(), 255, "标题");
        String summary = input == null ? "" : shortValue(input.summary(), 600, "摘要");
        String markdown = input == null ? "" : body(input.bodyMarkdown());
        String tagData = tagsJson(input == null ? null : input.tags());
        String cover = input == null ? null : input.coverUrl();
        validateCover(cover);
        Instant now = Instant.now();
        db.update("""
            INSERT INTO articles (id,slug,status,version,draft_title,draft_summary,draft_body,draft_tags,draft_cover,created_at,updated_at)
            VALUES (?,?, 'DRAFT', 0,?,?,?,?,?,?,?)
            """, id, slug, title, summary, markdown, tagData, cover, Timestamp.from(now), Timestamp.from(now));
        return getAdmin(id);
    }

    @Transactional(readOnly = true)
    public AdminArticle getAdmin(String id) { return admin(required(id, false)); }

    @Transactional(readOnly = true)
    public Page<AdminArticle> listAdmin(int page, int pageSize, String status) {
        pagination(page, pageSize);
        String where = status == null || status.isBlank() ? "" : " WHERE status = ?";
        if (!where.isEmpty() && !List.of("DRAFT", "PUBLISHED").contains(status)) throw bad("无效的文章状态。");
        long total = where.isEmpty() ? db.queryForObject("SELECT COUNT(*) FROM articles", Long.class)
                : db.queryForObject("SELECT COUNT(*) FROM articles" + where, Long.class, status);
        String sql = "SELECT * FROM articles" + where + " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?";
        List<Row> rows = where.isEmpty() ? db.query(sql, ArticleService::map, pageSize, (page - 1) * pageSize)
                : db.query(sql, ArticleService::map, status, pageSize, (page - 1) * pageSize);
        return new Page<>(rows.stream().map(this::admin).toList(), page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public Page<PublicArticle> listPublic(int page, int pageSize) {
        pagination(page, pageSize);
        long total = db.queryForObject("SELECT COUNT(*) FROM articles WHERE status='PUBLISHED'", Long.class);
        List<PublicArticle> items = db.query("""
            SELECT * FROM articles WHERE status='PUBLISHED'
            ORDER BY published_at DESC, id DESC LIMIT ? OFFSET ?
            """, ArticleService::map, pageSize, (page - 1) * pageSize).stream()
                .map(this::published).map(r -> new PublicArticle(r.id(), r.slug(), r.title(), r.summary(),
                        null, r.tags(), r.coverUrl(), r.publishedAt(), r.publicUpdatedAt())).toList();
        return new Page<>(items, page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public PublicArticle getPublic(String slug) {
        List<Row> rows = db.query("SELECT * FROM articles WHERE slug=? AND status='PUBLISHED'", ArticleService::map, slug);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "文章不存在。");
        return published(rows.getFirst());
    }

    @Transactional
    public AdminArticle save(String id, ArticleInput input) {
        Row current = required(id, true);
        if (input == null || input.version() == null || current.version() != input.version()) throw conflict();
        String slug = input.slug() == null ? current.slug() : input.slug().trim();
        validateSlug(slug);
        if (current.publishedAt() != null && !current.slug().equals(slug)) throw bad("首次发布后不能修改地址别名。");
        String title = shortValue(input.title(), 255, "标题");
        String summary = shortValue(input.summary(), 600, "摘要");
        String markdown = body(input.bodyMarkdown());
        String tagData = tagsJson(input.tags());
        validateCover(input.coverUrl());
        db.update("""
            UPDATE articles SET slug=?,draft_title=?,draft_summary=?,draft_body=?,draft_tags=?,draft_cover=?,
            version=version+1,updated_at=? WHERE id=?
            """, slug, title, summary, markdown, tagData, input.coverUrl(), Timestamp.from(Instant.now()), id);
        return getAdmin(id);
    }

    @Transactional
    public AdminArticle publish(String id, VersionInput input) {
        Row current = required(id, true);
        checkVersion(input, current);
        if (current.draftTitle().isBlank() || current.draftBody().isBlank() || current.slug().startsWith("draft-"))
            throw bad("发布前请填写标题、正文和正式地址别名。");
        Set<String> assetIds = assetIds(current.draftBody(), current.draftCover());
        for (String assetId : assetIds) {
            Integer count = db.queryForObject("SELECT COUNT(*) FROM assets WHERE id=? AND article_id=?", Integer.class,
                    assetId, id);
            if (count == null || count == 0)
                throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_REFERENCE_INVALID", "正文引用的图片无效。");
        }
        Instant now = Instant.now();
        db.update("""
            UPDATE articles SET status='PUBLISHED', public_title=draft_title, public_summary=draft_summary,
            public_body=draft_body, public_tags=draft_tags, public_cover=draft_cover,
            published_at=COALESCE(published_at, ?), public_updated_at=?, version=version+1, updated_at=?
            WHERE id=?
            """, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), id);
        db.update("DELETE FROM published_assets WHERE article_id=?", id);
        for (String assetId : assetIds)
            db.update("INSERT INTO published_assets (article_id,asset_id) VALUES (?,?)", id, assetId);
        return getAdmin(id);
    }

    @Transactional
    public AdminArticle unpublish(String id, VersionInput input) {
        Row current = required(id, true);
        checkVersion(input, current);
        if (!current.status().equals("PUBLISHED")) throw bad("文章尚未发布。");
        db.update("UPDATE articles SET status='DRAFT',version=version+1,updated_at=? WHERE id=?",
                Timestamp.from(Instant.now()), id);
        return getAdmin(id);
    }

    private static void pagination(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 50 || page > Integer.MAX_VALUE / pageSize)
            throw bad("分页参数无效。");
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "ARTICLE_VERSION_CONFLICT", "文章已被更新，请刷新后重试。");
    }

    private static void checkVersion(VersionInput input, Row current) {
        if (input == null || input.expectedVersion() == null || current.version() != input.expectedVersion()) throw conflict();
    }

    private Row required(String id, boolean lock) {
        List<Row> rows = db.query("SELECT * FROM articles WHERE id=?" + (lock ? " FOR UPDATE" : ""),
                ArticleService::map, id);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "文章不存在。");
        return rows.getFirst();
    }

    private static void validateCover(String cover) {
        if (cover != null && cover.length() > 1000) throw bad("封面地址过长。");
    }

    private static Set<String> assetIds(String markdown, String cover) {
        Set<String> ids = new LinkedHashSet<>();
        Node root = MARKDOWN.parse(markdown);
        List<Node> pending = new ArrayList<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            if (node instanceof Image image) collect(ids, image.getDestination());
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) pending.add(child);
        }
        if (cover != null) collect(ids, cover);
        return ids;
    }

    private static void collect(Set<String> ids, String url) {
        if (url == null) return;
        Matcher matcher = ASSET_URL.matcher(url);
        if (matcher.matches()) {
            try { ids.add(UUID.fromString(matcher.group(1)).toString()); }
            catch (IllegalArgumentException ex) { throw bad("图片地址无效。"); }
        } else if (url.startsWith("/api/v1/") && url.contains("/assets/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_REFERENCE_INVALID", "图片地址无效。");
        }
    }
}
