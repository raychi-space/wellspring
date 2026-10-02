package space.raychi.wellspring.dto;

import java.time.Instant;
import java.util.List;

public record AdminArticle(String id, String slug, String type, String status, long version,
                           String title, String summary, String bodyMarkdown, List<String> tags,
                           String coverUrl, String category, boolean hasUnpublishedChanges,
                           Instant createdAt, Instant updatedAt, Instant publishedAt, Instant publicUpdatedAt) {}
