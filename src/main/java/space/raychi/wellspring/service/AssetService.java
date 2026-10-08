package space.raychi.wellspring.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Iterator;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.dto.AssetContent;
import space.raychi.wellspring.dto.UploadedAsset;
import space.raychi.wellspring.entity.ArticleEntity;
import space.raychi.wellspring.entity.AssetEntity;
import space.raychi.wellspring.mapper.ArticleMapper;
import space.raychi.wellspring.mapper.AssetMapper;

@Service
public class AssetService {
    private final AssetMapper assets;
    private final ArticleMapper articles;
    private final Path root;

    public AssetService(AssetMapper assets, ArticleMapper articles, @Value("${raychi.assets.dir}") String directory) {
        this.assets = assets;
        this.articles = articles;
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    private record ImageMeta(String mediaType, int width, int height) {}

    public UploadedAsset upload(String articleId, MultipartFile file) {
        ArticleEntity article = articles.selectById(articleId, false).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ARTICLE_NOT_FOUND", "文章不存在。"));
        if (!article.type().equals("ARTICLE"))
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_INVALID", "帖子和思考不支持图片上传。");
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
            assets.insert(new AssetEntity(id, articleId, key, meta.mediaType(), bytes.length, meta.width(), meta.height(), Instant.now()));
        } catch (Exception ex) {
            try { Files.deleteIfExists(temp); Files.deleteIfExists(target); } catch (IOException ignored) {}
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "ASSET_STORE_FAILED", "图片保存失败。", ex);
        }
        return new UploadedAsset(id, articleId, publicUrl(id), "/api/v1/admin/assets/" + id + "/content",
                meta.mediaType(), bytes.length, meta.width(), meta.height());
    }

    public AssetContent read(String id, boolean admin) {
        AssetEntity asset = assets.selectById(id).orElseThrow(AssetService::notFound);
        if (!admin && !assets.isPublishedReference(asset.id(), asset.articleId())) throw notFound();
        try {
            return new AssetContent(Files.readAllBytes(root.resolve(asset.key())), asset.mediaType());
        } catch (IOException ex) { throw notFound(); }
    }

    /** Internal archive read; filesystem corruption cannot exceed the declared budget. */
    public byte[] readForExport(String id, long expectedBytes) throws IOException {
        var asset = assets.selectById(id).orElseThrow(AssetService::notFound);
        try (var input = Files.newInputStream(root.resolve(asset.key()))) {
            byte[] bytes = input.readNBytes(Math.toIntExact(expectedBytes) + 1);
            if (bytes.length != expectedBytes) throw new IOException("Asset size mismatch");
            return bytes;
        }
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
