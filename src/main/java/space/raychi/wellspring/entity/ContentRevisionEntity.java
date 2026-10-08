package space.raychi.wellspring.entity;

import java.time.Instant;

public record ContentRevisionEntity(String id, String articleId, long articleVersion, String operation,
    String title, String summary, String bodyMarkdown, String tagsJson, String category, String coverUrl, Instant createdAt) {}
