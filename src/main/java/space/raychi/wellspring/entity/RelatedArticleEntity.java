package space.raychi.wellspring.entity;

import java.time.Instant;

public record RelatedArticleEntity(String id, String slug, String title, String summary,
                                   String category, Instant publishedAt) {}
