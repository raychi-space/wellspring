package space.raychi.wellspring.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Heading;
import org.commonmark.node.Text;
import org.commonmark.node.Code;
import space.raychi.wellspring.mapper.PublicationSummaryMapper;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.api.PageResponse;
import space.raychi.wellspring.dto.AdminArticle;
import space.raychi.wellspring.dto.ArticleInput;
import space.raychi.wellspring.dto.PublicArticle;
import space.raychi.wellspring.dto.VersionInput;
import space.raychi.wellspring.entity.ArticleDraftEntity;
import space.raychi.wellspring.entity.ArticleEntity;
import space.raychi.wellspring.mapper.ArticleMapper;
import space.raychi.wellspring.mapper.AssetMapper;
import space.raychi.wellspring.mapper.TaxonomyMapper.Kind;
import space.raychi.wellspring.mapper.TaxonomyMapper;

@Service
public class ArticleService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern ASSET_URL = Pattern.compile("^/api/v1/public/assets/([0-9a-fA-F-]{36})/content$");
    private static final Parser MARKDOWN = Parser.builder().extensions(List.of(TablesExtension.create())).build();
    private final ArticleMapper articles;
    private final TaxonomyMapper taxonomy;
    private final AssetMapper assets;
    private final ObjectMapper json;
    private final space.raychi.wellspring.search.SearchState search;
    private final PublicationSummaryMapper summaryJobs;
    private final PublicationSummaryService summaries;

    public ArticleService(ArticleMapper articles, TaxonomyMapper taxonomy, AssetMapper assets, ObjectMapper json, space.raychi.wellspring.search.SearchState search, PublicationSummaryMapper summaryJobs, PublicationSummaryService summaries) {
        this.articles = articles;
        this.taxonomy = taxonomy;
        this.assets = assets;
        this.json = json;
        this.search = search;
        this.summaryJobs = summaryJobs;
        this.summaries = summaries;
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

    private AdminArticle admin(ArticleEntity r) {
        boolean changed = r.publicUpdatedAt() == null ||
                !Objects.equals(r.draftTitle(), r.publicTitle()) ||
                !Objects.equals(r.draftSummary(), r.publicSummary()) ||
                !Objects.equals(r.draftBody(), r.publicBody()) ||
                !Objects.equals(r.draftTags(), r.publicTags()) ||
                !Objects.equals(r.draftCover(), r.publicCover()) ||
                !Objects.equals(r.draftCategory(), r.publicCategory());
        var job = summaryJobs.get(r.id()).orElse(null);
        return new AdminArticle(r.id(), r.slug(), displayType(r.type()), r.status(), r.version(), r.draftTitle(), r.draftSummary(),
                r.draftBody(), tags(r.draftTags()), r.draftCover(), displayCategory(r.type(), r.draftCategory()), changed,
                r.createdAt(), r.updatedAt(), r.publishedAt(), r.publicUpdatedAt(),
                job == null ? "NONE" : job.status(), job == null ? null : job.errorCode());
    }

    private PublicArticle published(ArticleEntity r) {
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
        if (!taxonomy.exists(Kind.CATEGORY, name)) throw bad("分类不存在。");
        return name;
    }

    private String checkedTags(String type, List<String> value, String previous) {
        if (type.equals("THOUGHT")) {
            if (value != null && !value.isEmpty()) throw bad("思考不使用标签。");
            return "[]";
        }
        String encoded = tagsJson(value);
        for (String name : tags(encoded)) {
            if (!taxonomy.exists(Kind.TAG, name) && !tags(previous).contains(name)) throw bad("标签不存在：" + name);
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
                ? (input == null || input.slug() == null || input.slug().isBlank() ? dateSlug(id) : input.slug().trim())
                : dateSlug(id);
        validateSlug(slug);
        String title = input == null ? "" : shortValue(input.title(), 255, "标题");
        String summary = input == null ? "" : shortValue(input.summary(), 600, "摘要");
        String markdown = input == null ? "" : body(input.bodyMarkdown());
        if (kind.equals("ARTICLE")) title = documentTitle(markdown, title);
        String tagData = checkedTags(kind, input == null ? null : input.tags(), null);
        String cover = input == null ? null : input.coverUrl();
        validateCover(cover);
        noImages(kind, markdown, cover);
        String selectedCategory = category(kind, input == null ? null : input.category());
        Instant now = Instant.now();
        try {
            articles.insertDraft(new ArticleDraftEntity(id, slug, kind, title, summary, markdown, tagData,
                    cover, selectedCategory, null, now));
        } catch (DuplicateKeyException ex) { throw slugConflict(); }
        return getAdmin(id);
    }

    @Transactional(readOnly = true)
    public AdminArticle getAdmin(String id) { return admin(required(id, false)); }

    @Transactional(readOnly = true)
    public PageResponse<AdminArticle> listAdmin(int page, int pageSize, String status) {
        return listAdmin(page, pageSize, status, "ARTICLE");
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminArticle> listAdmin(int page, int pageSize, String status, String contentType) {
        return listAdmin(page, pageSize, status, contentType, null, null, "recent");
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminArticle> listAdmin(int page, int pageSize, String status, String contentType, String category, String tag, String sort) {
        pagination(page, pageSize);
        if (status != null && !status.isBlank() && !List.of("DRAFT", "PUBLISHED").contains(status)) throw bad("无效的内容状态。");
        if (contentType != null) type(contentType);
        if (!List.of("recent", "oldest", "type", "category", "tag").contains(sort)) throw bad("无效的排序方式。");
        long total = articles.countAdmin(status, contentType, category, tag);
        var rows = articles.selectAdmin(status, contentType, category, tag, sort, pageSize, (page - 1) * pageSize);
        return new PageResponse<>(rows.stream().map(this::admin).toList(), page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public PageResponse<PublicArticle> listPublic(int page, int pageSize) {
        PageResponse<PublicArticle> result = listPublic(page, pageSize, "ARTICLE", null, null);
        return new PageResponse<>(result.items().stream().map(r -> new PublicArticle(r.id(), r.slug(), r.type(),
                r.title(), r.summary(), null, r.tags(), r.coverUrl(), r.category(),
                r.publishedAt(), r.publicUpdatedAt())).toList(), result.page(), result.pageSize(), result.total());
    }

    @Transactional(readOnly = true)
    public PageResponse<PublicArticle> listPublic(int page, int pageSize, String contentType, String category, String tag) {
        pagination(page, pageSize);
        if (contentType != null) type(contentType);
        return listPublic(page, pageSize, contentType, category, tag, "latest");
    }

    @Transactional(readOnly = true)
    public PageResponse<PublicArticle> listPublic(int page, int pageSize, String contentType, String category, String tag, String sort) {
        pagination(page, pageSize);
        if (contentType != null) type(contentType);
        if (!List.of("latest", "oldest", "title").contains(sort)) throw bad("无效的排序方式。");
        long total = articles.countPublished(contentType, category, tag);
        var rows = articles.selectPublished(contentType, category, tag, sort, pageSize, (page - 1) * pageSize);
        return new PageResponse<>(rows.stream().map(this::published).toList(), page, pageSize, total);
    }

    @Transactional(readOnly = true)
    public PublicArticle getPublic(String slug) {
        return getPublic("ARTICLE", slug);
    }

    @Transactional(readOnly = true)
    public PublicArticle getPublic(String contentType, String slug) {
        String kind = type(contentType);
        return published(articles.selectPublishedBySlug(kind, slug).orElseThrow(ArticleService::notFound));
    }

    @Transactional
    public AdminArticle save(String id, ArticleInput input) {
        return save(required(id, true), input);
    }

    private AdminArticle save(ArticleEntity current, ArticleInput input) {
        String id = current.id();
        if (input == null || input.version() == null || current.version() != input.version()) throw conflict();
        String slug = current.type().equals("ARTICLE") && input.slug() != null ? input.slug().trim() : current.slug();
        if (current.publishedAt() == null && slug.startsWith("draft-")) slug = dateSlug(id);
        validateSlug(slug);
        if (current.publishedAt() != null && !current.slug().equals(slug)) throw bad("首次发布后不能修改地址别名。");
        String title = shortValue(input.title(), 255, "标题");
        String summary = shortValue(input.summary(), 600, "摘要");
        String markdown = body(input.bodyMarkdown());
        if (current.type().equals("ARTICLE")) title = documentTitle(markdown, documentTitle(current.draftBody(), "").isBlank() ? title : "");
        String effectiveType = displayType(current.type());
        String tagData = checkedTags(effectiveType, input.tags(), current.draftTags());
        validateCover(input.coverUrl());
        noImages(effectiveType, markdown, input.coverUrl());
        String selectedCategory = category(effectiveType, input.category() == null
                ? displayCategory(current.type(), current.draftCategory()) : input.category());
        try {
            articles.updateDraft(new ArticleDraftEntity(id, slug, effectiveType, title, summary, markdown, tagData,
                    input.coverUrl(), selectedCategory, displayCategory(current.type(), current.publicCategory()), Instant.now()));
        } catch (DuplicateKeyException ex) { throw slugConflict(); }
        return getAdmin(id);
    }

    @Transactional
    public AdminArticle publish(String id, VersionInput input) {
        return publish(required(id, true), input);
    }

    private AdminArticle publish(ArticleEntity current, VersionInput input) {
        String id = current.id();
        checkVersion(input, current);
        if ((current.type().equals("ARTICLE") && (current.draftTitle().isBlank() || current.slug().startsWith("draft-")))
                || current.draftBody().isBlank())
            throw bad("发布前请填写正文，并在文章正文中添加标题。");
        if (input.assistantId() != null && !input.assistantId().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}"))
            throw bad("助手标识无效。");
        Set<String> assetIds = assetIds(current.draftBody(), current.draftCover());
        for (String assetId : assetIds) {
            if (!assets.belongsToArticle(assetId, id))
                throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_REFERENCE_INVALID", "正文引用的图片无效。");
        }
        Instant now = Instant.now();
        articles.publish(id, displayType(current.type()), displayCategory(current.type(), current.draftCategory()), now);
        articles.replacePublishedTags(id, tags(current.draftTags()));
        assets.replacePublishedReferences(id, assetIds);
        search.capture(id, false);
        summaries.enqueue(id, input.assistantId());
        return getAdmin(id);
    }

    @Transactional
    public AdminArticle unpublish(String id, VersionInput input) {
        return unpublish(required(id, true), input);
    }

    private AdminArticle unpublish(ArticleEntity current, VersionInput input) {
        String id = current.id();
        checkVersion(input, current);
        if (!current.status().equals("PUBLISHED")) throw bad("文章尚未发布。");
        articles.unpublish(id, Instant.now());
        summaryJobs.cancel(id);
        search.capture(id, false);
        return getAdmin(id);
    }

    @Transactional
    public void delete(String id, VersionInput input) {
        ArticleEntity current = required(id, true);
        checkVersion(input, current);
        search.capture(id, true);
        summaryJobs.delete(id);
        assets.deleteByArticle(id);
        articles.delete(id);
    }

    private static void pagination(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 50 || page > Integer.MAX_VALUE / pageSize)
            throw bad("分页参数无效。");
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "ARTICLE_VERSION_CONFLICT", "文章已被更新，请刷新后重试。");
    }

    private static void checkVersion(VersionInput input, ArticleEntity current) {
        if (input == null || input.expectedVersion() == null || current.version() != input.expectedVersion()) throw conflict();
    }

    private ArticleEntity required(String id, boolean lock) {
        return articles.selectById(id, lock).orElseThrow(ArticleService::notFound);
    }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "文章不存在。");
    }

    private static ApiException slugConflict() {
        return new ApiException(HttpStatus.CONFLICT, "SLUG_CONFLICT", "地址别名已存在。");
    }

    @Transactional(readOnly = true)
    public AdminArticle getLegacyArticle(String id) {
        return admin(requiredLegacyArticle(id, false));
    }

    @Transactional
    public AdminArticle saveLegacyArticle(String id, ArticleInput input) {
        return save(requiredLegacyArticle(id, true), input);
    }

    @Transactional
    public AdminArticle publishLegacyArticle(String id, VersionInput input) {
        return publish(requiredLegacyArticle(id, true), input);
    }

    @Transactional
    public AdminArticle unpublishLegacyArticle(String id, VersionInput input) {
        return unpublish(requiredLegacyArticle(id, true), input);
    }

    private ArticleEntity requiredLegacyArticle(String id, boolean lock) {
        ArticleEntity value = required(id, lock);
        if (!"ARTICLE".equals(value.type())) throw notFound();
        return value;
    }

    private static String dateSlug(String id) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.now()) + "-" + id.substring(0, 8);
    }

    /** First top-level CommonMark heading; ignore fenced code, quotes, inline HTML and image URLs. */
    private static String documentTitle(String markdown, String fallback) {
        for (Node node = MARKDOWN.parse(markdown).getFirstChild(); node != null; node = node.getNext()) {
            if (!(node instanceof Heading)) continue;
            StringBuilder text = new StringBuilder();
            node.accept(new AbstractVisitor() {
                @Override public void visit(Text n) { text.append(n.getLiteral()); }
                @Override public void visit(Code n) { text.append(n.getLiteral()); }
                @Override public void visit(Image n) {}
            });
            return shortValue(text.toString(), 200, "正文标题");
        }
        // Old API clients and pre-existing articles can retain their separately entered title.
        return fallback;
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
