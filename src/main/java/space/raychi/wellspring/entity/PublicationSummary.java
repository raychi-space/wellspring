package space.raychi.wellspring.entity;

import java.time.Instant;

public record PublicationSummary(String articleId, String jobId, String assistantId,
        Instant publicationAt, String status, String taskId, Instant startedAt, String errorCode) {}
