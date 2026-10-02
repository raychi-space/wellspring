package space.raychi.wellspring.api;

import java.util.List;

public record PageResponse<T>(List<T> items, int page, int pageSize, long total) {}
