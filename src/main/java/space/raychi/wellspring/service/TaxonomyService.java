package space.raychi.wellspring.service;

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
    public TaxonomyService(TaxonomyMapper taxonomy) { this.taxonomy = taxonomy; }

    @Transactional(readOnly = true)
    public List<NameDto> categories() { return list(Kind.CATEGORY); }

    @Transactional(readOnly = true)
    public List<NameDto> tags() { return list(Kind.TAG); }

    @Transactional
    public NameDto createCategory(NameDto input) { return create(Kind.CATEGORY, input, 80); }

    @Transactional
    public NameDto createTag(NameDto input) { return create(Kind.TAG, input, 40); }

    private List<NameDto> list(Kind kind) {
        return taxonomy.selectAll(kind).stream().map(value -> new NameDto(value.name())).toList();
    }

    private NameDto create(Kind kind, NameDto input, int max) {
        String name = input == null || input.name() == null ? "" : input.name().trim();
        if (name.isEmpty() || name.length() > max || name.chars().anyMatch(Character::isISOControl))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "名称不能为空或超出长度限制。");
        if (taxonomy.exists(kind, name)) throw conflict();
        try { taxonomy.insert(kind, new TaxonomyEntity(name, Instant.now())); }
        catch (DuplicateKeyException ex) { throw conflict(); }
        return new NameDto(name);
    }

    private static ApiException conflict() {
        return new ApiException(HttpStatus.CONFLICT, "NAME_CONFLICT", "名称已存在。");
    }
}
