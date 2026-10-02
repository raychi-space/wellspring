package space.raychi.wellspring.entity;

import java.time.Instant;

public record ArticleDraftEntity(String id, String slug, String type, String title, String summary,
                             String body, String tags, String cover, String category,
                             String publicCategory, Instant updatedAt) {}
