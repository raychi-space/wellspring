package space.raychi.wellspring.entity;

import java.time.Instant;

public record ArticleEntity(String id, String slug, String type, String status, long version,
                            String draftTitle, String draftSummary, String draftBody, String draftTags, String draftCover,
                            String draftCategory, String publicTitle, String publicSummary, String publicBody,
                            String publicTags, String publicCover, String publicCategory,
                            Instant createdAt, Instant updatedAt, Instant publishedAt, Instant publicUpdatedAt) {}
