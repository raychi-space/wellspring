package space.raychi.wellspring.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import space.raychi.wellspring.entity.ArticleDraftEntity;
import space.raychi.wellspring.entity.ArticleEntity;
import space.raychi.wellspring.entity.TaxonomyEntity;
import space.raychi.wellspring.mapper.ArticleMapper;
import space.raychi.wellspring.mapper.ContentRevisionMapper;
import space.raychi.wellspring.mapper.TaxonomyMapper;
import space.raychi.wellspring.search.SearchState;
import space.raychi.wellspring.service.ContentHistoryService;

/** Offline, additive import. Does not start HTTP, workers, models or database migrations. */
public final class HistoricalContentImport {
    public record Entry(String sourcePath, String slug, String title, String summary, String bodyMarkdown,
            String bodySha256, String category, List<String> tags, String publishedAt, String modifiedAt) {}
    public record Manifest(int schemaVersion, String sourceOrigin, List<Entry> entries) {}
    public record Result(int inserted, int unchanged) {}
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) {
        try {
            if (args.length != 2 || !List.of("--check", "--apply", "--verify").contains(args[0]))
                throw new IllegalArgumentException("Use --check|--apply|--verify manifest.json");
            Path file = Path.of(args[1]);
            if (Files.size(file) > 32_000_000) throw new IllegalArgumentException("Manifest too large");
            Manifest manifest = JSON.readValue(Files.readAllBytes(file), Manifest.class);
            Map<String, String> env = System.getenv();
            String url = required(env, "RAYCHI_IMPORT_DB_URL");
            if (!url.matches("jdbc:postgresql://[^/]+/[a-zA-Z0-9_]+"))
                throw new IllegalArgumentException("Explicit PostgreSQL database URL required");
            String database = url.substring(url.lastIndexOf('/') + 1);
            if (!database.equals(required(env, "RAYCHI_IMPORT_DB_CONFIRM")))
                throw new IllegalArgumentException("Database confirmation mismatch");
            try (Connection connection = DriverManager.getConnection(url, required(env, "RAYCHI_IMPORT_DB_USER"),
                    required(env, "RAYCHI_IMPORT_DB_PASSWORD"))) {
                Result result = run(connection, manifest, args[0]);
                System.out.println("PASS " + args[0] + " inserted=" + result.inserted() + " unchanged=" + result.unchanged());
            }
        } catch (Exception error) {
            // SQL/manifest errors can contain credentials or content: print only the type.
            System.err.println("Historical import failed (" + error.getClass().getSimpleName() + "); no partial batch committed.");
            System.exit(1);
        }
    }

    private static String required(Map<String, String> env, String key) {
        String value = env.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    public static String identity(String origin, String path) {
        return UUID.nameUUIDFromBytes(("raychi:historical:" + origin + path).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static void validate(Manifest manifest) throws Exception {
        URI origin = URI.create(manifest.sourceOrigin());
        if (manifest.schemaVersion() != 1 || !"https".equals(origin.getScheme()) || origin.getHost() == null
                || origin.getRawUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
                || !Objects.equals(origin.getPath(), "") || manifest.entries() == null
                || manifest.entries().isEmpty() || manifest.entries().size() > 1000)
            throw new IllegalArgumentException("Invalid manifest");
        var paths = new HashSet<String>(); var slugs = new HashSet<String>();
        for (Entry e : manifest.entries()) {
            if (e.sourcePath() == null || !e.sourcePath().matches("/(?:[a-z0-9][a-z0-9-]*/)*")
                    || !paths.add(e.sourcePath()) || e.slug() == null || e.slug().length() > 120
                    || !e.slug().matches("[a-z0-9]+(?:-[a-z0-9]+)*") || !slugs.add(e.slug())
                    || e.title() == null || e.title().isBlank() || e.title().length() > 255
                    || e.summary() == null || e.summary().length() > 600 || e.category() == null
                    || e.category().isBlank() || e.category().length() > 80 || e.bodyMarkdown() == null
                    || e.bodyMarkdown().length() > 1_000_000 || e.bodyMarkdown().contains("\u0000")
                    || e.tags() == null || e.tags().size() > 20 || new HashSet<>(e.tags()).size() != e.tags().size())
                throw new IllegalArgumentException("Invalid entry");
            for (String tag : e.tags()) if (tag == null || tag.isBlank() || tag.length() > 40)
                throw new IllegalArgumentException("Invalid tag");
            if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(e.bodyMarkdown().getBytes(StandardCharsets.UTF_8))).equals(e.bodySha256()))
                throw new IllegalArgumentException("Body digest mismatch");
            Instant first = Instant.parse(e.publishedAt()), modified = Instant.parse(e.modifiedAt());
            if (modified.isBefore(first) || first.isAfter(Instant.now().plusSeconds(3600)))
                throw new IllegalArgumentException("Invalid publication time");
            // This command supports text-only reviewed manifests; missing attachments must not be silently ignored.
            var pending = new java.util.ArrayList<org.commonmark.node.Node>();
            pending.add(org.commonmark.parser.Parser.builder().build().parse(e.bodyMarkdown()));
            while (!pending.isEmpty()) {
                var node = pending.removeLast();
                if (node instanceof org.commonmark.node.Image
                        || node instanceof org.commonmark.node.HtmlBlock || node instanceof org.commonmark.node.HtmlInline)
                    throw new IllegalArgumentException("Images/raw HTML require an explicit asset migration");
                for (var child = node.getFirstChild(); child != null; child = child.getNext()) pending.add(child);
            }
        }
    }

    public static Result run(Connection connection, Manifest manifest, String mode) throws Exception {
        if (!List.of("--check", "--apply", "--verify").contains(mode)) throw new IllegalArgumentException("Invalid mode");
        validate(manifest);
        if (!connection.getAutoCommit()) throw new IllegalArgumentException("A dedicated connection is required");
        connection.setAutoCommit(false);
        try {
            connection.createStatement().execute("SET LOCAL lock_timeout='5s'");
            connection.createStatement().execute("SET LOCAL statement_timeout='30s'");
            var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var articles = new ArticleMapper(db); var taxonomy = new TaxonomyMapper(db);
            var history = new ContentHistoryService(articles, new ContentRevisionMapper(db), JSON);
            var search = new SearchState(db, JSON);
            int inserted = 0, unchanged = 0;
            for (Entry e : manifest.entries()) {
                String id = identity(manifest.sourceOrigin(), e.sourcePath());
                var existing = articles.selectById(id, true);
                if (existing.isPresent()) {
                    verify(existing.get(), e); unchanged++; continue;
                }
                if (mode.equals("--verify")) throw new IllegalStateException("Missing imported entry");
                if (db.queryForObject("SELECT count(*) FROM articles WHERE slug=?", Long.class, e.slug()) != 0)
                    throw new IllegalStateException("Existing slug collision");
                Instant first = Instant.parse(e.publishedAt()), modified = Instant.parse(e.modifiedAt());
                taxonomy.insertIfAbsent(TaxonomyMapper.Kind.CATEGORY, new TaxonomyEntity(e.category(), first));
                for (String tag : e.tags()) taxonomy.insertIfAbsent(TaxonomyMapper.Kind.TAG, new TaxonomyEntity(tag, first));
                articles.insertDraft(new ArticleDraftEntity(id, e.slug(), "ARTICLE", e.title(), e.summary(),
                        e.bodyMarkdown(), JSON.writeValueAsString(e.tags()), null, e.category(), null, first));
                articles.publish(id, "ARTICLE", e.category(), first);
                db.update("UPDATE articles SET updated_at=?,public_updated_at=? WHERE id=?",
                        Timestamp.from(modified), Timestamp.from(modified), id);
                articles.replacePublishedTags(id, e.tags());
                history.record(articles.selectById(id, false).orElseThrow(), "PUBLISH");
                search.capture(id, false);
                verify(articles.selectById(id, false).orElseThrow(), e); inserted++;
            }
            if (mode.equals("--apply")) connection.commit(); else connection.rollback();
            return new Result(inserted, unchanged);
        } catch (Exception error) {
            connection.rollback(); throw error;
        } finally { connection.setAutoCommit(true); }
    }

    private static void verify(ArticleEntity row, Entry e) throws Exception {
        if (!"ARTICLE".equals(row.type()) || !"PUBLISHED".equals(row.status()) || row.version() != 1
                || !Objects.equals(row.slug(), e.slug()) || !Objects.equals(row.draftTitle(), e.title())
                || !Objects.equals(row.publicTitle(), e.title()) || !Objects.equals(row.draftSummary(), e.summary())
                || !Objects.equals(row.publicSummary(), e.summary()) || !Objects.equals(row.draftBody(), e.bodyMarkdown())
                || !Objects.equals(row.publicBody(), e.bodyMarkdown()) || row.draftCover() != null || row.publicCover() != null
                || !Objects.equals(row.draftCategory(), e.category()) || !Objects.equals(row.publicCategory(), e.category())
                || !JSON.readTree(row.draftTags()).equals(JSON.valueToTree(e.tags()))
                || !JSON.readTree(row.publicTags()).equals(JSON.valueToTree(e.tags()))
                || !row.publishedAt().equals(Instant.parse(e.publishedAt()))
                || !row.createdAt().equals(Instant.parse(e.publishedAt()))
                || !row.publicUpdatedAt().equals(Instant.parse(e.modifiedAt()))
                || !row.updatedAt().equals(Instant.parse(e.modifiedAt())))
            throw new IllegalStateException("Imported content differs; refusing to overwrite");
    }
}
