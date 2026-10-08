package space.raychi.wellspring.dto;

import java.time.Instant;

/** Public metadata only; no body or working-draft fields. */
public record RelatedArticle(String id, String slug, String title, String summary,
                             String category, Instant publishedAt) {}
