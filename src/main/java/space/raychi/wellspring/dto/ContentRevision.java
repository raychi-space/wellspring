package space.raychi.wellspring.dto;

import java.time.Instant;

public record ContentRevision(String id, long articleVersion, String operation, String title, String summary,
    Instant createdAt, ContentArchive.Snapshot snapshot) {}
