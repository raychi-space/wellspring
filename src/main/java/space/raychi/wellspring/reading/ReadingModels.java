package space.raychi.wellspring.reading;

import java.time.Instant;
import java.util.List;

public final class ReadingModels {
    private ReadingModels() {}

    public record Settings(long version, int displayDays, String translationAssistantId) {}

    public record Source(String id, String name, String description, String siteUrl, String listingUrl,
                         String adapter, boolean enabled, int position, Instant lastCheckedAt,
                         Instant lastSuccessAt, String lastError, Instant createdAt, Instant updatedAt) {}

    public record Article(String id, String sourceId, String externalUrl, String originalTitle,
                          String translatedTitle, Instant publishedAt, Instant discoveredAt,
                          Instant updatedAt, String titleHash, String translationStatus,
                          String translationTaskId, int translationAttempts,
                          Instant nextTranslationAt, int translationRevision) {}

    public record PublicArticle(String id, String url, String originalTitle, String translatedTitle,
                                Instant publishedAt, String illustrationSeed) {}

    public record PublicSource(String id, String name, String description, String siteUrl,
                               List<PublicArticle> articles) {}

    public record PublicShelf(int displayDays, List<PublicSource> sources) {}

    public record AdminSource(String id, String name, String description, String siteUrl,
                              String listingUrl, String adapter, boolean enabled, int position,
                              Instant lastCheckedAt, Instant lastSuccessAt, String lastError,
                              int articleCount) {}

    public record AdminShelf(Settings settings, List<AdminSource> sources) {}

    public record SourceInput(String name, String description, String siteUrl, String listingUrl,
                              String adapter, boolean enabled, int position) {}

    public record SettingsInput(long version, String translationAssistantId) {}

    public record ParsedArticle(String url, String title, Instant publishedAt) {}
}
