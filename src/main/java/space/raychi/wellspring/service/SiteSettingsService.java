package space.raychi.wellspring.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.dto.SiteSettingsDto.Homepage;
import space.raychi.wellspring.dto.SiteSettingsDto.Link;
import space.raychi.wellspring.dto.SiteSettingsDto.Project;
import space.raychi.wellspring.dto.SiteSettingsDto.Section;
import space.raychi.wellspring.dto.SiteSettingsDto.Settings;
import space.raychi.wellspring.dto.SiteSettingsDto.SocialAccount;
import space.raychi.wellspring.entity.SiteSettingsEntity;
import space.raychi.wellspring.mapper.SiteSettingsMapper;

@Service
public class SiteSettingsService {
    private final SiteSettingsMapper settings;
    private final ObjectMapper json;

    public SiteSettingsService(SiteSettingsMapper settings, ObjectMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public Settings publicSettings() { return read(); }

    @Transactional(readOnly = true)
    public Settings adminSettings() { return read(); }

    private Settings read() {
        SiteSettingsEntity value = settings.select();
        return decode(value.valueJson(), value.version());
    }

    private Settings decode(String stored, long version) {
        try {
            Settings value = json.readValue(stored, Settings.class);
            List<SocialAccount> socialAccounts = new ArrayList<>(value.socialAccounts());
            List<Link> accounts = new ArrayList<>();
            for (Link link : value.accounts()) {
                String platform = link.label().trim().toLowerCase(Locale.ROOT);
                if (List.of("github", "x", "bilibili", "youtube", "zhihu", "juejin", "xiaohongshu", "mastodon")
                        .contains(platform) && link.href().matches("https?://[^\\s]+")) {
                    if (socialAccounts.stream().noneMatch(account -> account.platform().equals(platform)))
                        socialAccounts.add(new SocialAccount(platform, true, link.href()));
                } else accounts.add(link);
            }
            return new Settings(version, value.siteName(), value.intro(), value.avatarUrl(),
                    value.contacts(), accounts, value.navigation(), value.homeSections(),
                    value.homepage(), value.projectIntro(), socialAccounts);
        } catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid site settings", ex); }
    }

    @Transactional
    public Settings save(Settings input) {
        Settings previous = read();
        Settings effective = input == null ? null : new Settings(input.version(), input.siteName(), input.intro(),
                input.avatarUrl(), input.contacts(), input.accounts(), input.navigation(), input.homeSections(),
                input.homepage() == null ? previous.homepage() : input.homepage(),
                input.projectIntro() == null ? previous.projectIntro() : input.projectIntro(),
                input.socialAccounts() == null ? previous.socialAccounts() : input.socialAccounts());
        validate(effective);
        try {
            int updated = settings.update(new SiteSettingsEntity(effective.version(), json.writeValueAsString(effective), Instant.now()));
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
        Homepage homepage = input.homepage();
        if (homepage == null || homepage.focus() == null || homepage.focus().length() > 160
                || homepage.projects() == null || homepage.projects().size() > 3
                || homepage.recentSections() == null || homepage.bottomSections() == null) bad();
        for (Project project : homepage.projects()) {
            if (project == null || project.name() == null || project.name().isBlank() || project.name().length() > 80
                    || project.description() == null || project.description().length() > 240
                    || project.status() == null || project.status().isBlank() || project.status().length() > 40
                    || project.href() == null || project.href().length() > 500 || !safeUrl(project.href())
                    || project.href().startsWith("mailto:")) bad();
        }
        validateSections(homepage.recentSections(), List.of("featured", "posts", "writing"));
        validateSections(homepage.bottomSections(), List.of("projects", "stats"));
        if (input.projectIntro() == null || input.projectIntro().length() > 240
                || input.socialAccounts() == null || input.socialAccounts().size() > 8) bad();
        HashSet<String> platforms = new HashSet<>();
        for (SocialAccount account : input.socialAccounts()) {
            if (account == null || account.platform() == null
                    || !List.of("github", "x", "bilibili", "youtube", "zhihu", "juejin", "xiaohongshu", "mastodon")
                        .contains(account.platform())
                    || !platforms.add(account.platform()) || account.href() == null || account.href().length() > 500
                    || (account.enabled() && (!account.href().matches("https?://[^\\s]+")))) bad();
        }
    }

    private static void validateSections(List<Section> values, List<String> ids) {
        if (values.size() != ids.size()) bad();
        HashSet<String> seen = new HashSet<>();
        for (Section section : values)
            if (section == null || !ids.contains(section.id()) || !seen.add(section.id())) bad();
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
