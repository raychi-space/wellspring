package space.raychi.wellspring.dto;
import java.time.Instant;
public record TrashItem(String id, String slug, String type, String title, long version,
                              Instant trashedAt, Instant updatedAt) {}
