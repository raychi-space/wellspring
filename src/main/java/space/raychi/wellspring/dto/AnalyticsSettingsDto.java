package space.raychi.wellspring.dto;

public final class AnalyticsSettingsDto {
    private AnalyticsSettingsDto() {}
    public record Settings(boolean enabled, boolean configured, long version) {}
}
