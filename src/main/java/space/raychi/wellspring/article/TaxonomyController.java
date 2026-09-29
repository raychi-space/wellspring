package space.raychi.wellspring.article;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import space.raychi.wellspring.api.ApiException;

@RestController
public class TaxonomyController {
    private final JdbcTemplate db;

    TaxonomyController(JdbcTemplate db) { this.db = db; }

    public record Name(String name) {}

    @GetMapping("/api/v1/public/categories")
    public List<Name> categories() { return list("categories"); }

    @GetMapping("/api/v1/public/tags")
    public List<Name> tags() { return list("tags"); }

    @PostMapping("/api/v1/admin/categories")
    public Name createCategory(@RequestBody Name input) { return create("categories", input, 80); }

    @PostMapping("/api/v1/admin/tags")
    public Name createTag(@RequestBody Name input) { return create("tags", input, 40); }

    private List<Name> list(String table) {
        return db.query("SELECT name FROM " + table + " ORDER BY name", (rs, n) -> new Name(rs.getString(1)));
    }

    private Name create(String table, Name input, int max) {
        String name = input == null || input.name() == null ? "" : input.name().trim();
        if (name.isEmpty() || name.length() > max || name.chars().anyMatch(Character::isISOControl))
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "名称不能为空或超出长度限制。");
        Integer count = db.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE name=?", Integer.class, name);
        if (count != null && count > 0)
            throw new ApiException(HttpStatus.CONFLICT, "NAME_CONFLICT", "名称已存在。");
        try {
            db.update("INSERT INTO " + table + " (name,created_at) VALUES (?,?)", name, Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException ex) {
            throw new ApiException(HttpStatus.CONFLICT, "NAME_CONFLICT", "名称已存在。");
        }
        return new Name(name);
    }
}
