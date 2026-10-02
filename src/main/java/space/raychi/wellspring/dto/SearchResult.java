package space.raychi.wellspring.dto;

import java.util.List;

public record SearchResult(List<Hit> hits, Integer nextOffset) {
    public record Hit(
            String id,
            long version,
            String type,
            String title,
            String snippet,
            double score,
            String href) {}



}
