package space.raychi.wellspring.article;

import space.raychi.wellspring.api.ApiException;

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
                               String bodyMarkdown, List<String> tags, String coverUrl, String category) {
        public ArticleInput(Long version, String slug, String title, String summary,
                            String bodyMarkdown, List<String> tags, String coverUrl) {
            this(version, slug, title, summary, bodyMarkdown, tags, coverUrl, null);
        }
    }
    public record VersionInput(Long expectedVersion) {}
    public record AdminArticle(String id, String slug, String type, String status, long version,
                               String title, String summary, String bodyMarkdown, List<String> tags,
                               String coverUrl, String category, boolean hasUnpublishedChanges,
                               Instant createdAt, Instant updatedAt, Instant publishedAt, Instant publicUpdatedAt) {}
    public record PublicArticle(String id, String slug, String type, String title, String summary,
                                String bodyMarkdown, List<String> tags, String coverUrl, String category,
                                Instant publishedAt, Instant publicUpdatedAt) {}
    public record Page<T>(List<T> items, int page, int pageSize, long total) {}

    private record Row(String id, String slug, String type, String status, long version,
                       String draftTitle, String draftSummary, String draftBody, String draftTags, String draftCover,
                       String draftCategory, String publicTitle, String publicSummary, String publicBody,
                       String publicTags, String publicCover, String publicCategory,
                       Instant createdAt, Instant updatedAt, Instant publishedAt, Instant publicUpdatedAt) {}

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        return new Row(rs.getString("id"), rs.getString("slug"), rs.getString("content_type"), rs.getString("status"), rs.getLong("version"),
                rs.getString("draft_title"), rs.getString("draft_summary"), rs.getString("draft_body"),
                rs.getString("draft_tags"), rs.getString("draft_cover"), rs.getString("draft_category"), rs.getString("public_title"),
                rs.getString("public_summary"), rs.getString("public_body"), rs.getString("public_tags"),
                rs.getString("public_cover"), rs.getString("public_category"), instant(rs, "created_at"), instant(rs, "updated_at"),
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
                !Objects.equals(r.draftCover(), r.publicCover()) ||
                !Objects.equals(r.draftCategory(), r.publicCategory());
        return new AdminArticle(r.id(), r.slug(), displayType(r.type()), r.status(), r.version(), r.draftTitle(), r.draftSummary(),
                r.draftBody(), tags(r.draftTags()), r.draftCover(), displayCategory(r.type(), r.draftCategory()), changed,
                r.createdAt(), r.updatedAt(), r.publishedAt(), r.publicUpdatedAt());
    }

    private PublicArticle published(Row r) {
        return new PublicArticle(r.id(), r.slug(), displayType(r.type()), r.publicTitle(), r.publicSummary(), r.publicBody(),
                tags(r.publicTags()), r.publicCover(), displayCategory(r.type(), r.publicCategory()), r.publishedAt(), r.publicUpdatedAt());
    }

    private static String displayType(String storedType) {
        return "THOUGHT".equals(storedType) ? "POST" : storedType;
    }

    private static String displayCategory(String storedType, String category) {
        return "THOUGHT".equals(storedType) ? null : category;
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

    private static String type(String value) {
        if (!List.of("ARTICLE", "POST", "THOUGHT").contains(value)) throw bad("无效的内容类型。");
        return value;
    }

    private String category(String type, String value) {
        if (type.equals("POST")) {
            if (value != null && !value.isBlank()) throw bad("帖子不使用分类。");
            return null;
        }
        String name = value == null || value.isBlank() ? "未分类" : shortValue(value, 80, "分类");
        Integer count = db.queryForObject("SELECT COUNT(*) FROM categories WHERE name=?", Integer.class, name);
        if (count == null || count == 0) throw bad("分类不存在。");
        return name;
    }

    private String checkedTags(String type, List<String> value, String previous) {
        if (type.equals("THOUGHT")) {
            if (value != null && !value.isEmpty()) throw bad("思考不使用标签。");
            return "[]";
        }
        String encoded = tagsJson(value);
        for (String name : tags(encoded)) {
            Integer count = db.queryForObject("SELECT COUNT(*) FROM tags WHERE name=?", Integer.class, name);
            if ((count == null || count == 0) && !tags(previous).contains(name)) throw bad("标签不存在：" + name);
        }
        return encoded;
    }

    private static void noImages(String type, String markdown, String cover) {
        if (type.equals("ARTICLE")) return;
        if (cover != null && !cover.isBlank()) throw bad("帖子和思考不支持封面。");
        Node root = MARKDOWN.parse(markdown);
        List<Node> pending = new ArrayList<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Node node = pending.removeLast();
            if (node instanceof Image) throw bad("帖子和思考不支持图片。");
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) pending.add(child);
        }
        if (markdown.matches("(?is).*<\\s*img\\b.*")) throw bad("帖子和思考不支持图片。");
    }

    @Transactional
    public AdminArticle create(ArticleInput input) {
        return create("ARTICLE", input);
    }

    @Transactional
    public AdminArticle create(String contentType, ArticleInput input) {
        String kind = type(contentType);
        if (kind.equals("THOUGHT")) throw bad("现在只支持创建文章和帖子。");
        String id = UUID.randomUUID().toString();
        String slug = kind.equals("ARTICLE")
                ? (input == null || input.slug() == null || input.slug().isBlank() ? "draft-" + id : input.slug().trim())
                : "post-" + id;
        validateSlug(slug);
        String title = input == null ? "" : shortValue(input.title(), 255, "标题");
        String summary = input == null ? "" : shortValue(input.summary(), 600, "摘要");
        String markdown = input == null ? "" : body(input.bodyMarkdown());
        String tagData = checkedTags(kind, input == null ? null : input.tags(), null);
        String cover = input == null ? null : input.coverUrl();
        validateCover(cover);
        noImages(kind, markdown, cover);
        String selectedCategory = category(kind, input == null ? null : input.category());
        Instant now = Instant.now();
        db.update("""
            INSERT INTO articles (id,slug,content_type,status,version,draft_title,draft_summary,draft_body,draft_tags,draft_cover,draft_category,created_at,updated_at)
            VALUES (?,?,?, 'DRAFT', 0,?,?,?,?,?,?,?,?)
            """, id, slug, kind, title, summary, markdown, tagData, cover, selectedCategory, Timestamp.from(now), Timestamp.from(now));
        return getAdmin(id);
    }

    @Transactional(readOnly = true)
    public AdminArticle getAdmin(String id) { return admin(required(id, false)); }

    @Transactional(readOnly = true)
    public Page<AdminArticle> listAdmin(int page, int pageSize, String status) {
        return listAdmin(page, pageSize, status, "ARTICLE");
    }

    @Transactional(readOnly = true)
    public Page<AdminArticle> listAdmin(int page, int pageSize, String status, String contentType) {
        pagination(page, pageSize);
        if (status != null && !status.isBlank() && !List.of("DRAFT", "PUBLISHED").contains(status)) throw bad("无效的内容状态。");
        String where = " WHERE 1=1" + (contentType == null ? "" : contentType.equals("POST")
                ? " AND content_type IN ('POST','THOUGHT')" : " AND content_type=?") +
                (status == null || status.isBlank() ? "" : " AND status=?");
        List<Object> args = new ArrayList<>();
        if (contentType != null && !contentType.equals("POST")) args.add(type(contentType));
        if (status != null && !status.isBlank()) args.add(status);
        long total = db.queryForObject("SELECT COUNT(*) FROM articles" + where, Long.class, args.toArray());
        String sql = "SELECT * FROM articles" + where + " ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?";
        args.add(pageSize);
        args.add((page - 1) * pageSize);
        List<Row> rows = db.query(sql, ArticleService::map, args.toArray());
        return new Page<>(rows.stream().map(this::admin).toList(), page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public Page<PublicArticle> listPublic(int page, int pageSize) {
        Page<PublicArticle> result = listPublic(page, pageSize, "ARTICLE", null, null);
        return new Page<>(result.items().stream().map(r -> new PublicArticle(r.id(), r.slug(), r.type(),
                r.title(), r.summary(), null, r.tags(), r.coverUrl(), r.category(),
                r.publishedAt(), r.publicUpdatedAt())).toList(), result.page(), result.pageSize(), result.total());
    }

    @Transactional(readOnly = true)
    public Page<PublicArticle> listPublic(int page, int pageSize, String contentType, String category, String tag) {
        pagination(page, pageSize);
        String where = " WHERE status='PUBLISHED'" + (contentType == null ? "" : contentType.equals("POST")
                ? " AND content_type IN ('POST','THOUGHT')" : " AND content_type=?") +
                (category == null || category.isBlank() ? "" : " AND content_type='ARTICLE' AND public_category=?");
        List<Object> args = new ArrayList<>();
        if (contentType != null && !contentType.equals("POST")) args.add(type(contentType));
        if (category != null && !category.isBlank()) args.add(category);
        // Tags are JSON arrays from v0.1; filter after reading to avoid dialect-specific JSON SQL.
        List<Row> rows = db.query("SELECT * FROM articles" + where + " ORDER BY published_at DESC, id DESC",
                ArticleService::map, args.toArray());
        List<PublicArticle> all = rows.stream().map(this::published)
                .filter(r -> tag == null || tag.isBlank() || r.tags().contains(tag)).toList();
        long total = all.size();
        List<PublicArticle> items = all.stream().skip((long) (page - 1) * pageSize).limit(pageSize)
                .map(r -> new PublicArticle(r.id(), r.slug(), r.type(), r.title(), r.summary(),
                        r.bodyMarkdown(), r.tags(), r.coverUrl(),
                        r.category(), r.publishedAt(), r.publicUpdatedAt())).toList();
        return new Page<>(items, page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public PublicArticle getPublic(String slug) {
        return getPublic("ARTICLE", slug);
    }

    @Transactional(readOnly = true)
    public PublicArticle getPublic(String contentType, String slug) {
        String kind = type(contentType);
        List<Row> rows = db.query("SELECT * FROM articles WHERE " + (kind.equals("POST")
                        ? "content_type IN ('POST','THOUGHT')" : "content_type=?") + " AND slug=? AND status='PUBLISHED'",
                ArticleService::map, kind.equals("POST") ? new Object[]{slug} : new Object[]{kind, slug});
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "文章不存在。");
        return published(rows.getFirst());
    }

    @Transactional
    public AdminArticle save(String id, ArticleInput input) {
        Row current = required(id, true);
        if (input == null || input.version() == null || current.version() != input.version()) throw conflict();
        String slug = current.type().equals("ARTICLE") && input.slug() != null ? input.slug().trim() : current.slug();
        validateSlug(slug);
        if (current.publishedAt() != null && !current.slug().equals(slug)) throw bad("首次发布后不能修改地址别名。");
        String title = shortValue(input.title(), 255, "标题");
        String summary = shortValue(input.summary(), 600, "摘要");
        String markdown = body(input.bodyMarkdown());
        String effectiveType = displayType(current.type());
        String tagData = checkedTags(effectiveType, input.tags(), current.draftTags());
        validateCover(input.coverUrl());
        noImages(effectiveType, markdown, input.coverUrl());
        String selectedCategory = category(effectiveType, input.category() == null
                ? displayCategory(current.type(), current.draftCategory()) : input.category());
        db.update("""
            UPDATE articles SET content_type=?,slug=?,draft_title=?,draft_summary=?,draft_body=?,draft_tags=?,draft_cover=?,draft_category=?,public_category=?,
            version=version+1,updated_at=? WHERE id=?
            """, effectiveType, slug, title, summary, markdown, tagData, input.coverUrl(), selectedCategory,
                displayCategory(current.type(), current.publicCategory()), Timestamp.from(Instant.now()), id);
        return getAdmin(id);
    }

    @Transactional
    public AdminArticle publish(String id, VersionInput input) {
        Row current = required(id, true);
        checkVersion(input, current);
        if ((current.type().equals("ARTICLE") && (current.draftTitle().isBlank() || current.slug().startsWith("draft-")))
                || current.draftBody().isBlank())
            throw bad("发布前请填写正文；长文还需要标题和正式地址别名。");
        Set<String> assetIds = assetIds(current.draftBody(), current.draftCover());
        for (String assetId : assetIds) {
            Integer count = db.queryForObject("SELECT COUNT(*) FROM assets WHERE id=? AND article_id=?", Integer.class,
                    assetId, id);
            if (count == null || count == 0)
                throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_REFERENCE_INVALID", "正文引用的图片无效。");
        }
        Instant now = Instant.now();
        db.update("""
            UPDATE articles SET content_type=?,draft_category=?,status='PUBLISHED', public_title=draft_title, public_summary=draft_summary,
            public_body=draft_body, public_tags=draft_tags, public_cover=draft_cover, public_category=?,
            published_at=COALESCE(published_at, ?), public_updated_at=?, version=version+1, updated_at=?
            WHERE id=?
            """, displayType(current.type()), displayCategory(current.type(), current.draftCategory()),
                displayCategory(current.type(), current.draftCategory()),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), id);
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
