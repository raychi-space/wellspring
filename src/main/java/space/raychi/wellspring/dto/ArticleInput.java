package space.raychi.wellspring.dto;

import java.util.List;

public record ArticleInput(Long version, String slug, String title, String summary,
                           String bodyMarkdown, List<String> tags, String coverUrl, String category, Boolean publicationMetadata) {
    public ArticleInput(Long version, String slug, String title, String summary, String bodyMarkdown, List<String> tags, String coverUrl, String category) {
        this(version, slug, title, summary, bodyMarkdown, tags, coverUrl, category, false);
    }
    public ArticleInput(Long version, String slug, String title, String summary,
                        String bodyMarkdown, List<String> tags, String coverUrl) {
        this(version, slug, title, summary, bodyMarkdown, tags, coverUrl, null);
    }
}
