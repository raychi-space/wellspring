package space.raychi.wellspring.dto;

import java.time.Instant;
import java.util.List;

public record ContentArchive(int schemaVersion, String id, String slug, String type, String status, long version,
        Instant createdAt, Instant updatedAt, Instant publishedAt, Instant publicUpdatedAt,
        Snapshot draft, Snapshot published, List<Attachment> attachments) {
    public record Snapshot(String title, String summary, String bodyMarkdown, List<String> tags, String category, String coverUrl) {}
    public record Attachment(String id, String path, String url, String mediaType, long byteSize, int width, int height, String sha256) {}
}
