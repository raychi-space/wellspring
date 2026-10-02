package space.raychi.wellspring.service;

import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import space.raychi.wellspring.mapper.AssetMapper;

@Component
public class AssetCleanupWorker {
    private static final Logger LOG = LoggerFactory.getLogger(AssetCleanupWorker.class);
    private final AssetMapper assets;
    private final Path root;
    public AssetCleanupWorker(AssetMapper assets, @Value("${raychi.assets.dir}") String directory) {
        this.assets = assets; this.root = Path.of(directory).toAbsolutePath().normalize();
    }
    @Scheduled(fixedDelayString = "${raychi.assets.cleanup-ms:30000}", initialDelayString = "${raychi.assets.cleanup-ms:30000}")
    public void clean() {
        for (String key : assets.pendingDeletions()) {
            // Only application-generated keys are deletable; never scan the storage directory.
            if (!key.matches("[0-9a-f]{32}")) { LOG.warn("Invalid asset cleanup key; queue entry retained"); continue; }
            try {
                Files.deleteIfExists(root.resolve(key));
                assets.completeDeletion(key);
            } catch (java.io.IOException ex) {
                LOG.warn("Asset cleanup failed; queue entry retained for retry", ex);
            }
        }
    }
}
