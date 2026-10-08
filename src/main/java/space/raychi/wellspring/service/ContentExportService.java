package space.raychi.wellspring.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.dto.ContentArchive;
import space.raychi.wellspring.dto.ContentDownload;
import space.raychi.wellspring.mapper.ArticleMapper;
import space.raychi.wellspring.mapper.AssetMapper;

@Service
public class ContentExportService {
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private final ArticleMapper articles;
    private final AssetMapper assets;
    private final AssetService files;
    private final ObjectMapper json;
    public ContentExportService(ArticleMapper articles, AssetMapper assets, AssetService files, ObjectMapper json) {
        this.articles = articles; this.assets = assets; this.files = files; this.json = json;
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public ContentDownload export(String id, long expectedVersion) {
        if (expectedVersion < 0) throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "版本无效。");
        var row = articles.selectById(id, true).orElseThrow(() ->
            new ApiException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "内容不存在。"));
        if (row.version() != expectedVersion)
            throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "内容已改变，请重新加载后导出。");
        var owned = assets.selectForArticle(id);
        if (owned.size() > 256 || owned.stream().anyMatch(asset -> asset.byteSize() < 0 || asset.byteSize() > MAX_BYTES)
                || owned.stream().mapToLong(asset -> asset.byteSize()).sum() > MAX_BYTES) throw tooLarge();
        try {
            var attachments = new ArrayList<ContentArchive.Attachment>();
            var entries = new ArrayList<Map<String, Object>>();
            var output = new ByteArrayOutputStream();
            String draft = row.draftBody(), published = row.publishedAt() == null ? null : row.publicBody();
            for (var asset : owned) {
                String extension = switch (asset.mediaType()) {
                    case "image/png" -> ".png";
                    case "image/jpeg" -> ".jpg";
                    default -> throw new IllegalStateException("Unexpected asset type");
                };
                if (!asset.id().matches("[0-9a-f-]{36}")) throw new IllegalStateException("Invalid asset id");
                String path = "assets/" + asset.id() + extension;
                String url = "/api/v1/public/assets/" + asset.id() + "/content";
                draft = draft.replace(url, path);
                if (published != null) published = published.replace(url, path);
            }
            try (var zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                add(zip, entries, "draft.md", draft.getBytes(StandardCharsets.UTF_8));
                if (published != null) add(zip, entries, "published.md", published.getBytes(StandardCharsets.UTF_8));
                for (var asset : owned) {
                    String path = "assets/" + asset.id() + (asset.mediaType().equals("image/png") ? ".png" : ".jpg");
                    byte[] bytes = files.readForExport(asset.id(), asset.byteSize());
                    if (bytes.length != asset.byteSize()) throw new IllegalStateException("Asset size mismatch");
                    add(zip, entries, path, bytes);
                    attachments.add(new ContentArchive.Attachment(asset.id(), path,
                        "/api/v1/public/assets/" + asset.id() + "/content", asset.mediaType(), bytes.length,
                        asset.width(), asset.height(), sha(bytes)));
                }
                boolean article = row.type().equals("ARTICLE");
                var archive = new ContentArchive(1, row.id(), row.slug(), article ? "ARTICLE" : "POST", row.status(), row.version(),
                    row.createdAt(), row.updatedAt(), row.publishedAt(), row.publicUpdatedAt(),
                    new ContentArchive.Snapshot(row.draftTitle(), row.draftSummary(), row.draftBody(), tags(row.draftTags()),
                        article ? row.draftCategory() : null, row.draftCover()),
                    row.publishedAt() == null ? null : new ContentArchive.Snapshot(row.publicTitle(), row.publicSummary(), row.publicBody(),
                        tags(row.publicTags()), article ? row.publicCategory() : null, row.publicCover()), attachments);
                add(zip, entries, "content.json", json.writerWithDefaultPrettyPrinter().writeValueAsBytes(archive));
                zip.putNextEntry(new ZipEntry("manifest.json"));
                zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(Map.of("schemaVersion", 1, "files", entries)));
                zip.closeEntry();
            }
            if (!row.id().matches("[0-9a-f-]{36}")) throw new IllegalStateException("Invalid content id");
            return new ContentDownload(output.toByteArray(), "raychi-" + row.id() + "-v" + row.version() + ".zip");
        } catch (ApiException ex) {
            if ("CONTENT_EXPORT_TOO_LARGE".equals(ex.code())) throw ex;
            throw failed(ex);
        } catch (Exception ex) { throw failed(ex); }
    }

    private List<String> tags(String raw) throws Exception {
        return raw == null ? List.of() : json.readValue(raw, new TypeReference<List<String>>() {});
    }
    private static void add(ZipOutputStream zip, List<Map<String, Object>> entries, String path, byte[] bytes) throws Exception {
        if (entries.stream().mapToLong(entry -> ((Number) entry.get("byteSize")).longValue()).sum() + bytes.length > MAX_BYTES)
            throw tooLarge();
        zip.putNextEntry(new ZipEntry(path)); zip.write(bytes); zip.closeEntry();
        entries.add(Map.of("path", path, "byteSize", bytes.length, "sha256", sha(bytes)));
    }
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "CONTENT_EXPORT_TOO_LARGE", "内容与图片超过单篇导出限制。");
    }
    private static ApiException failed(Exception cause) {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "CONTENT_EXPORT_FAILED", "无法生成完整导出包，请检查附件后重试。", cause);
    }
}
