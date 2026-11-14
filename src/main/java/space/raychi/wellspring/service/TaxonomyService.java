package space.raychi.wellspring.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.Set;
import java.util.Map;
import java.util.HashSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import space.raychi.wellspring.dto.TaxonomyManagement.Item;
import space.raychi.wellspring.dto.TaxonomyManagement.Reference;
import space.raychi.wellspring.dto.TaxonomyManagement.Rename;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.dto.NameDto;
import space.raychi.wellspring.entity.TaxonomyEntity;
import space.raychi.wellspring.mapper.TaxonomyMapper.Kind;
import space.raychi.wellspring.mapper.TaxonomyMapper;

@Service
public class TaxonomyService {
    private final TaxonomyMapper taxonomy;
    private final ObjectMapper json;
    public TaxonomyService(TaxonomyMapper taxonomy, ObjectMapper json) {
        this.taxonomy = taxonomy; this.json = json;
    }

    public static Kind kind(String value) {
        return switch (value) {
            case "categories" -> Kind.CATEGORY;
            case "tags" -> Kind.TAG;
            default -> throw new ApiException(HttpStatus.NOT_FOUND, "TAXONOMY_NOT_FOUND", "目录不存在。");
        };
    }

    private Set<String> rowNames(Kind kind, TaxonomyMapper.ReferenceRow row) {
        if (kind == Kind.CATEGORY) return row.category() == null ? Set.of() : Set.of(row.category());
        if (row.tags() == null) return Set.of();
        try { return new HashSet<>(json.readValue(row.tags(), new TypeReference<List<String>>() {})); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid stored tags", ex); }
    }

    @Transactional(readOnly = true)
    public List<Item> management(Kind kind) {
        var references = new HashMap<String, Map<String, Set<String>>>();
        for (var row : taxonomy.referenceRows()) {
            String scope = row.status().equals("TRASHED") ? "trash" : row.scope();
            for (String name : rowNames(kind, row))
                references.computeIfAbsent(name, key -> new HashMap<>())
                    .computeIfAbsent(scope, key -> new HashSet<>()).add(row.id());
        }
        return taxonomy.selectAll(kind).stream().map(value -> {
            var groups = references.getOrDefault(value.name(), Map.of());
            var all = new HashSet<String>();
            groups.values().forEach(all::addAll);
            return new Item(value.name(),
                groups.getOrDefault("draft", Set.of()).size(), groups.getOrDefault("public", Set.of()).size(),
                groups.getOrDefault("trash", Set.of()).size(), groups.getOrDefault("history", Set.of()).size(),
                all.size(), kind == Kind.CATEGORY && value.name().equals("未分类"));
        }).toList();
    }

    @Transactional(readOnly = true)
    public List<Reference> references(Kind kind, String name) {
        require(kind, name);
        var unique = new LinkedHashMap<String, Reference>();
        for (var row : taxonomy.referenceRows()) if (rowNames(kind, row).contains(name))
            unique.putIfAbsent(row.id(), new Reference(row.id(), row.title(), row.status()));
        return List.copyOf(unique.values());
    }

    @Transactional
    public void rename(Kind kind, Rename input) {
        taxonomy.lock();
        String name = input == null ? null : input.name();
        mutable(kind, name);
        String next = validated(kind, input.newName());
        if (taxonomy.exists(kind, next)) throw conflict();
        try { taxonomy.rename(kind, name, next); }
        catch (DuplicateKeyException ex) { throw conflict(); }
    }

    @Transactional
    public void delete(Kind kind, String name) {
        taxonomy.lock();
        mutable(kind, name);
        taxonomy.delete(kind, name);
    }

    private void require(Kind kind, String name) {
        if (name == null || !taxonomy.exists(kind, name))
            throw new ApiException(HttpStatus.NOT_FOUND, "TAXONOMY_NOT_FOUND", "分类或标签不存在。");
    }
    private void mutable(Kind kind, String name) {
        require(kind, name);
        if (kind == Kind.CATEGORY && name.equals("未分类"))
            throw new ApiException(HttpStatus.CONFLICT, "TAXONOMY_PROTECTED", "未分类不能更名或删除。");
        if (taxonomy.referenceRows().stream().anyMatch(row -> rowNames(kind, row).contains(name)))
            throw new ApiException(HttpStatus.CONFLICT, "TAXONOMY_IN_USE", "此项仍被工作稿、快照、回收站或历史版本引用。");
    }
    private static String validated(Kind kind, String value) {
        String name = value == null ? "" : value.trim();
        if (name.isEmpty() || name.length() > (kind == Kind.CATEGORY ? 80 : 40) || name.chars().anyMatch(Character::isISOControl))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "名称不能为空或超出长度限制。");
        return name;
    }

    @Transactional(readOnly = true)
    public List<NameDto> categories() { return list(Kind.CATEGORY); }

    @Transactional(readOnly = true)
    public List<NameDto> tags() { return list(Kind.TAG); }

    @Transactional
    public NameDto createCategory(NameDto input) { return create(Kind.CATEGORY, input); }

    @Transactional
    public NameDto createTag(NameDto input) { return create(Kind.TAG, input); }

    private List<NameDto> list(Kind kind) {
        return taxonomy.selectAll(kind).stream().map(value -> new NameDto(value.name())).toList();
    }

    private NameDto create(Kind kind, NameDto input) {
        taxonomy.lock();
        String name = validated(kind, input == null ? null : input.name());
        if (taxonomy.exists(kind, name)) throw conflict();
        try { taxonomy.insert(kind, new TaxonomyEntity(name, Instant.now())); }
        catch (DuplicateKeyException ex) { throw conflict(); }
        return new NameDto(name);
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "NAME_CONFLICT", "名称已存在。");
    }
}
