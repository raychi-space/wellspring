package space.raychi.wellspring.entity;

import java.time.Instant;

public record SiteSettingsEntity(long version, String valueJson, Instant updatedAt) {}
