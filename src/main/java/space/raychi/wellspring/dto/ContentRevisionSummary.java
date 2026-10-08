package space.raychi.wellspring.dto;

import java.time.Instant;

public record ContentRevisionSummary(String id, long articleVersion, String operation, String title, String summary, Instant createdAt) {}
