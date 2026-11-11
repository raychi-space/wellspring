package space.raychi.wellspring.reading;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import jakarta.validation.constraints.*;
import java.util.List;

/** Site-owned allowlisted DTOs. No upstream config, credential, model request or process details. */
public final class ReadingManagementModels {
    private ReadingManagementModels() {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProcessState(String status, String lastSeenAt) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Processes(ProcessState api, ProcessState worker) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceHealth(long total, long enabled, long healthy, long failing, long degraded) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Counts(long collectedToday, long pending, long published) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Trend(String date, long collected, long processed) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Task(String id, String kind, String name, String status, String startedAt, String finishedAt, String error) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TaskPage(int page, int pageSize, long total, List<Task> items) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(long calls, long ok, long failed, long unknown, Long p50Ms, Long p95Ms, long tokensIn, long tokensOut) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Budget(String service, int perMinute, int perHour, int perDay, long usedMinute, long usedHour, long usedDay, String updatedAt) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Dashboard(String checkedAt, String timeZone, Processes processes, SourceHealth sources, Counts counts,
            List<Trend> trend, TaskPage tasks, Usage modelSummary, List<Budget> budgets) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SourceRow(String id, String name, boolean enabled, String health, int intervalMinutes,
            String lastSuccessAt, String lastFetchAt, String nextFetchAt, String lastError) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Sources(int page, int pageSize, long total, List<SourceRow> items) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String id, String name, boolean enabled, String health, int intervalMinutes,
            String lastSuccessAt, String lastFetchAt, String nextFetchAt, String lastError, String feedUrl, String version) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Check(long count, long elapsedMs) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Collection(String jobId) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModelChoice(String key, String label, String service, boolean vision) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capability(String key, String label, String currentKey, String currentLabel, boolean configured, Usage usage) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Models(String checkedAt, boolean metadataAvailable, String metadataAt, int days, List<ModelChoice> choices,
            List<Capability> capabilities, List<Budget> budgets, Usage summary) {}

    public record SourceCreate(@NotBlank @Size(max=200) String name, @NotBlank @Size(max=2048) String feedUrl,
            @NotNull @Min(1) @Max(1440) @JsonDeserialize(using=StrictInteger.class) Integer intervalMinutes) {}
    public record SourceUpdate(@NotBlank @Size(max=200) String name, @NotBlank @Size(max=2048) String feedUrl,
            @NotNull @Min(1) @Max(1440) @JsonDeserialize(using=StrictInteger.class) Integer intervalMinutes, @NotNull Boolean enabled,
            @NotBlank @Size(max=40) String version) {}
    public record BudgetInput(@NotNull @Min(0) @Max(1000000) @JsonDeserialize(using=StrictInteger.class) Integer perMinute,
            @NotNull @Min(0) @Max(1000000) @JsonDeserialize(using=StrictInteger.class) Integer perHour,
            @NotNull @Min(0) @Max(1000000) @JsonDeserialize(using=StrictInteger.class) Integer perDay) {}

    /** Reject fractional or string limits rather than silently rounding a budget. */
    public static final class StrictInteger extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) return context.reportInputMismatch(Integer.class,"An integer is required");
            return parser.getIntValue();
        }
    }
}
