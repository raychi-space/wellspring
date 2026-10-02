package space.raychi.wellspring.dto;

public record UploadedAsset(String id, String articleId, String url, String previewUrl,
                       String mediaType, long byteSize, int width, int height) {}
