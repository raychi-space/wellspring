package space.raychi.wellspring.entity;

import java.time.Instant;

public record AssetEntity(String id, String articleId, String key, String mediaType,
                      long byteSize, int width, int height, Instant createdAt) {}
