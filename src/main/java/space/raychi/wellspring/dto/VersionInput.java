package space.raychi.wellspring.dto;

public record VersionInput(Long expectedVersion, String assistantId, Boolean metadataReviewed) {
    public VersionInput(Long expectedVersion, String assistantId) { this(expectedVersion, assistantId, false); }
    public VersionInput(Long expectedVersion) { this(expectedVersion, null, false); }
}
