package space.raychi.wellspring.service;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.dto.SiteSettingsDto.Settings;
import space.raychi.wellspring.mapper.ProfileAvatarMapper;

@Service
public class ProfileAvatarService {
    private final ProfileAvatarMapper avatars;
    private final SiteSettingsService settings;
    public ProfileAvatarService(ProfileAvatarMapper avatars, SiteSettingsService settings) {
        this.avatars = avatars; this.settings = settings;
    }
    @Transactional
    public Settings save(MultipartFile file, long version) {
        if (version < 0)
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请提供当前站点设置版本。");
        if (file == null || file.isEmpty())
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_INVALID", "请选择头像图片。");
        if (file.getSize() > 2 * 1024 * 1024)
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "ASSET_TOO_LARGE", "头像不能超过 2 MiB。");
        byte[] bytes;
        try {
            bytes = file.getBytes();
            var meta = AssetService.inspect(bytes);
            var source = ImageIO.read(new ByteArrayInputStream(bytes));
            double scale = Math.min(1.0, 512.0 / Math.max(meta.width(), meta.height()));
            var image = new BufferedImage(Math.max(1, (int)(meta.width() * scale)),
                    Math.max(1, (int)(meta.height() * scale)), BufferedImage.TYPE_INT_ARGB);
            var graphics = image.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(source, 0, 0, image.getWidth(), image.getHeight(), null);
            } finally { graphics.dispose(); }
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) throw new java.io.IOException("PNG encoder unavailable");
            bytes = output.toByteArray();
        } catch (java.io.IOException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ASSET_INVALID", "图片读取失败。");
        }
        avatars.lock();
        String id = UUID.randomUUID().toString();
        Settings saved = settings.saveAvatar("/api/v1/public/avatar/" + id + "/content", version);
        avatars.save(id, bytes);
        return saved;
    }
    @Transactional(readOnly = true)
    public byte[] read(String id) {
        byte[] bytes = avatars.read(id);
        if (bytes == null) throw new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "头像不存在。");
        return bytes;
    }
}
