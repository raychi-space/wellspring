package space.raychi.wellspring.dto;

public record VersionInput(Long expectedVersion, String assistantId) {
    public VersionInput(Long expectedVersion) { this(expectedVersion, null); }
}
