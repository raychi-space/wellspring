package space.raychi.wellspring.reading;

import static space.raychi.wellspring.reading.ReadingModels.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import space.raychi.wellspring.api.ApiException;

@Service
public class ReadingService {
    private static final Set<String> ADAPTERS = Set.of("OPENAI_DEVELOPERS", "ANTHROPIC_ENGINEERING");
    private final ReadingMapper data;
    private final ReadingSourceClient client;
    private final ReadingSourceParser parser;

    public ReadingService(ReadingMapper data, ReadingSourceClient client, ReadingSourceParser parser) {
        this.data = data; this.client = client; this.parser = parser;
    }

    @Transactional(readOnly = true)
    public PublicShelf publicShelf() {
        Settings settings = data.settings();
        Instant since = Instant.now().minus(Duration.ofDays(settings.displayDays()));
        List<PublicSource> sources = data.sources(true).stream().map(source -> new PublicSource(
                source.id(), source.name(), source.description(), source.siteUrl(),
                data.publicArticles(source.id(), since).stream().map(article -> new PublicArticle(
                        article.id(), article.externalUrl(), article.originalTitle(), article.translatedTitle(),
                        article.publishedAt(), article.titleHash().substring(0, 16))).toList())).toList();
        return new PublicShelf(settings.displayDays(), sources);
    }

    @Transactional(readOnly = true)
    public AdminShelf adminShelf() {
        return new AdminShelf(data.settings(), data.sources(false).stream().map(source -> new AdminSource(
                source.id(), source.name(), source.description(), source.siteUrl(), source.listingUrl(),
                source.adapter(), source.enabled(), source.position(), source.lastCheckedAt(),
                source.lastSuccessAt(), source.lastError(), data.articleCount(source.id()))).toList());
    }

    @Transactional
    public Settings saveSettings(SettingsInput input) {
        if (input == null || input.version() < 0 || (input.translationAssistantId() != null
                && !input.translationAssistantId().isBlank()
                && !input.translationAssistantId().matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}"))) bad();
        if (data.updateSettings(input, Instant.now()) != 1)
            throw new ApiException(HttpStatus.CONFLICT, "READING_SETTINGS_VERSION_CONFLICT", "拾阅设置已被更新，请刷新后重试。");
        return data.settings();
    }

    @Transactional
    public AdminSource create(SourceInput input) {
        SourceInput value = validated(input);
        Instant now = Instant.now();
        Source source = new Source(UUID.randomUUID().toString(), value.name(), value.description(), value.siteUrl(),
                value.listingUrl(), value.adapter(), value.enabled(), value.position(), null, null, null, now, now);
        data.insertSource(source);
        return admin(source);
    }

    @Transactional
    public AdminSource update(String id, SourceInput input) {
        SourceInput value = validated(input);
        if (data.updateSource(id(id), value, Instant.now()) != 1) notFound();
        return admin(data.source(id).orElseThrow());
    }

    @Transactional
    public void delete(String id) { if (data.deleteSource(id(id)) != 1) notFound(); }

    public AdminSource sync(String id) {
        Source source = data.source(id(id)).orElseThrow(ReadingService::missing);
        syncSource(source);
        return admin(data.source(id).orElseThrow());
    }

    public void syncEnabled() {
        for (Source source : data.sources(true)) {
            try { syncSource(source); }
            catch (ApiException ignored) { /* Failure is stored per source; continue with the others. */ }
        }
    }

    private void syncSource(Source source) {
        Instant now = Instant.now();
        try {
            String html = client.fetch(source);
            List<ParsedArticle> articles = parser.parse(source.adapter(), source.listingUrl(), html, now);
            if (articles.isEmpty()) throw new ReadingSourceClient.SourceFailure("NO_ARTICLES_FOUND");
            Instant since = now.minus(Duration.ofDays(data.settings().displayDays()));
            for (ParsedArticle article : articles)
                if (!article.publishedAt().isBefore(since)) upsert(source.id(), article, now);
            data.syncSuccess(source.id(), now);
        } catch (ReadingSourceClient.SourceFailure ex) {
            data.syncFailure(source.id(), now, ex.code());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "READING_SOURCE_SYNC_FAILED", "来源检查失败，已保留上次成功结果。");
        }
    }

    private void upsert(String sourceId, ParsedArticle article, Instant now) {
        String hash = sha256(article.title());
        Optional<Article> previous = data.byUrl(article.url());
        if (previous.isEmpty()) {
            try {
                data.insertArticle(new Article(UUID.randomUUID().toString(), sourceId, article.url(), article.title(), null,
                        article.publishedAt(), now, now, hash, "PENDING", null, 0, now, 0));
            } catch (DuplicateKeyException concurrentInsert) {
                Article inserted = data.byUrl(article.url()).orElseThrow(() -> concurrentInsert);
                data.refreshArticle(inserted, sourceId, article.title(), article.publishedAt(), hash, now);
            }
        } else data.refreshArticle(previous.get(), sourceId, article.title(), article.publishedAt(), hash, now);
    }

    private SourceInput validated(SourceInput input) {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().length() > 120
                || input.description() == null || input.description().length() > 240
                || input.siteUrl() == null || input.siteUrl().length() > 1000
                || input.listingUrl() == null || input.listingUrl().length() > 1000
                || input.adapter() == null || !ADAPTERS.contains(input.adapter())
                || input.position() < 0 || input.position() > 10000) bad();
        try {
            ReadingSourceClient.validate(input.adapter(), input.listingUrl());
            URI site = URI.create(input.siteUrl());
            URI listing = URI.create(input.listingUrl());
            if (!"https".equals(site.getScheme()) || !Objects.equals(site.getHost(), listing.getHost())) bad();
        } catch (IllegalArgumentException | ReadingSourceClient.SourceFailure ex) { bad(); }
        return new SourceInput(input.name().trim(), input.description().trim(), input.siteUrl(), input.listingUrl(),
                input.adapter(), input.enabled(), input.position());
    }

    private AdminSource admin(Source source) {
        return new AdminSource(source.id(), source.name(), source.description(), source.siteUrl(), source.listingUrl(),
                source.adapter(), source.enabled(), source.position(), source.lastCheckedAt(), source.lastSuccessAt(),
                source.lastError(), data.articleCount(source.id()));
    }

    private static String id(String value) {
        if (value == null || !value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}")) bad();
        return value;
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private static void bad() { throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "拾阅来源包含无效字段。"); }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "READING_SOURCE_NOT_FOUND", "拾阅来源不存在。"); }
    private static void notFound() { throw missing(); }
}
