package space.raychi.wellspring.dto;

import java.time.Instant;
import java.util.List;

public record PublicArticle(String id, String slug, String type, String title, String summary,
                            String bodyMarkdown, List<String> tags, String coverUrl, String category,
                            Instant publishedAt, Instant publicUpdatedAt) {}
