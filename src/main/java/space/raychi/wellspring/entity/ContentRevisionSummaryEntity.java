package space.raychi.wellspring.entity;

import java.time.Instant;

public record ContentRevisionSummaryEntity(String id, long articleVersion, String operation,
    String title, String summary, Instant createdAt) {}
