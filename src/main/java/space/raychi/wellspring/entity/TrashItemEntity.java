package space.raychi.wellspring.entity;
import java.time.Instant;
public record TrashItemEntity(String id, String slug, String type, String title, long version,
                              Instant trashedAt, Instant updatedAt) {}
