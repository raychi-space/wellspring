package space.raychi.wellspring.site;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import space.raychi.wellspring.api.ApiException;

@RestController
public class SiteSettingsController {
    private final JdbcTemplate db;
    private final ObjectMapper json;

    SiteSettingsController(JdbcTemplate db, ObjectMapper json) { this.db = db; this.json = json; }

    public record Link(String label, String href) {}
    public record Section(String id, boolean visible) {}
    public record Settings(long version, String siteName, String intro, String avatarUrl,
                           List<Link> contacts, List<Link> accounts, List<Link> navigation,
                           List<Section> homeSections) {}

    @GetMapping("/api/v1/public/settings")
    public Settings publicSettings() { return read(); }

    @GetMapping("/api/v1/admin/settings")
    public Settings adminSettings() { return read(); }

    private Settings read() {
        return db.queryForObject("SELECT value_json, version FROM site_settings WHERE id=1", (row, index) ->
                decode(row.getString("value_json"), row.getLong("version")));
    }

    private Settings decode(String stored, long version) {
        try {
            Settings value = json.readValue(stored, Settings.class);
            return new Settings(version, value.siteName(), value.intro(), value.avatarUrl(),
                    value.contacts(), value.accounts(), value.navigation(), value.homeSections());
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid site settings", ex); }
    }

    @PutMapping("/api/v1/admin/settings")
    @Transactional
    public Settings save(@RequestBody Settings input) {
        validate(input);
        try {
            int updated = db.update("UPDATE site_settings SET value_json=?, version=version+1, updated_at=? WHERE id=1 AND version=?",
                    json.writeValueAsString(input), Timestamp.from(Instant.now()), input.version());
            if (updated != 1)
                throw new ApiException(HttpStatus.CONFLICT, "SETTINGS_VERSION_CONFLICT", "设置已被更新，请刷新后重试。");
        } catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
        return read();
    }

    private static void validate(Settings input) {
        if (input == null || input.siteName() == null || input.siteName().isBlank() || input.siteName().length() > 100
                || input.intro() == null || input.intro().length() > 300
                || input.contacts() == null || input.accounts() == null || input.navigation() == null
                || input.homeSections() == null || input.contacts().size() > 10 || input.accounts().size() > 10
                || input.navigation().size() > 10 || input.homeSections().size() != 4) bad();
        if (input.avatarUrl() != null && !input.avatarUrl().isBlank() &&
                !(safeUrl(input.avatarUrl()) && !input.avatarUrl().startsWith("mailto:"))) bad();
        for (Link link : input.contacts()) validateLink(link);
        for (Link link : input.accounts()) validateLink(link);
        for (Link link : input.navigation()) validateLink(link);
        HashSet<String> labels = new HashSet<>();
        for (Link link : input.navigation()) if (!labels.add(link.label())) bad();
        HashSet<String> sections = new HashSet<>();
        for (Section section : input.homeSections()) {
            if (section == null || !List.of("feed", "writing", "posts", "thoughts").contains(section.id())
                    || !sections.add(section.id())) bad();
        }
    }

    private static void validateLink(Link link) {
        if (link == null || link.label() == null || link.label().isBlank() || link.label().length() > 40
                || link.href() == null || link.href().length() > 500 || !safeUrl(link.href())) bad();
    }

    private static boolean safeUrl(String url) {
        return url.startsWith("/") && !url.startsWith("//") && !url.contains("\\")
                || url.matches("https?://[^\\s]+") || url.matches("mailto:[^\\s@]+@[^\\s@]+");
    }

    private static void bad() {
        throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "站点设置有无效字段。");
    }
}
