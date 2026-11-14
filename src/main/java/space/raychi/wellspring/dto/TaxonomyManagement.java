package space.raychi.wellspring.dto;

public final class TaxonomyManagement {
    private TaxonomyManagement() {}
    public record Item(String name, long draftCount, long publicCount, long trashCount, long historyCount,
                       long referenceCount, boolean protectedName) {}
    public record Rename(String name, String newName) {}
    public record Reference(String id, String title, String status) {}
}
