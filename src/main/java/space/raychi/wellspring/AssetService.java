package space.raychi.wellspring;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AssetService {
    private final JdbcTemplate db;
    private final Path root;

    AssetService(JdbcTemplate db, @Value("${raychi.assets.dir}") String directory) {
        this.db = db;
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    public record Uploaded(String id, String articleId, String url, String previewUrl,
                           String mediaType, long byteSize, int width, int height) {}
    public record Content(byte[] bytes, String mediaType) {}
    private record Stored(String id, String articleId, String key, String mediaType) {}
    private record ImageMeta(String mediaType, int width, int height) {}

    public Uploaded upload(String articleId, MultipartFile file) {
        Integer articleCount = db.queryForObject("SELECT COUNT(*) FROM articles WHERE id=?", Integer.class, articleId);
        if (articleCount == null || articleCount == 0)
            throw new ApiException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "文章不存在。");
        if (file == null || file.isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_INVALID", "请选择图片。");
        if (file.getSize() > 10 * 1024 * 1024)
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ASSET_TOO_LARGE", "图片不能超过 10 MiB。");
        byte[] bytes;
        try { bytes = file.getBytes(); }
        catch (IOException ex) { throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_INVALID", "图片读取失败。"); }
        ImageMeta meta = inspect(bytes);
        String id = UUID.randomUUID().toString();
        String key = id.replace("-", "");
        Path temp = root.resolve(key + ".part");
        Path target = root.resolve(key);
        try {
            Files.createDirectories(root);
            Files.write(temp, bytes);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ex) { Files.move(temp, target); }
            db.update("""
                INSERT INTO assets (id,article_id,storage_key,media_type,byte_size,width_px,height_px,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, id, articleId, key, meta.mediaType(), bytes.length, meta.width(), meta.height(),
                    Timestamp.from(Instant.now()));
        } catch (Exception ex) {
            try { Files.deleteIfExists(temp); Files.deleteIfExists(target); } catch (IOException ignored) {}
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ASSET_STORE_FAILED", "图片保存失败。");
        }
        return new Uploaded(id, articleId, publicUrl(id), "/api/v1/admin/assets/" + id + "/content",
                meta.mediaType(), bytes.length, meta.width(), meta.height());
    }

    public Content read(String id, boolean admin) {
        List<Stored> matches = db.query("SELECT id,article_id,storage_key,media_type FROM assets WHERE id=?",
                (rs, rowNum) -> new Stored(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), id);
        if (matches.isEmpty()) throw notFound();
        Stored asset = matches.getFirst();
        if (!admin) {
            Integer allowed = db.queryForObject("""
                SELECT COUNT(*) FROM published_assets pa JOIN articles a ON a.id=pa.article_id
                WHERE pa.asset_id=? AND pa.article_id=? AND a.status='PUBLISHED'
                """, Integer.class, asset.id(), asset.articleId());
            if (allowed == null || allowed == 0) throw notFound();
        }
        try {
            return new Content(Files.readAllBytes(root.resolve(asset.key())), asset.mediaType());
        } catch (IOException ex) { throw notFound(); }
    }

    static String publicUrl(String id) { return "/api/v1/public/assets/" + id + "/content"; }

    private static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "图片不存在。");
    }

    private static ImageMeta inspect(byte[] bytes) {
        try (ImageInputStream stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid();
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                String format = reader.getFormatName().toLowerCase();
                String type = switch (format) {
                    case "png" -> "image/png";
                    case "jpeg", "jpg" -> "image/jpeg";
                    default -> throw invalid();
                };
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || (long) width * height > 16_000_000L) throw invalid();
                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw invalid();
                return new ImageMeta(type, width, height);
            } finally { reader.dispose(); }
        } catch (ApiException ex) { throw ex; }
        catch (Exception ex) { throw invalid(); }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "ASSET_INVALID", "图片格式无效；当前支持 PNG 和 JPEG。");
    }
}
