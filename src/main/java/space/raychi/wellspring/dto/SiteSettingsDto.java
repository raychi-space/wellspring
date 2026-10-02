package space.raychi.wellspring.dto;

import java.util.List;

public final class SiteSettingsDto {
    private SiteSettingsDto() {}

    public record Link(String label, String href) {}
    public record SocialAccount(String platform, boolean enabled, String href) {}
    public record Section(String id, boolean visible) {}
    public record Project(String name, String description, String status, String href) {}
    public record Homepage(String focus, List<Project> projects, List<Section> recentSections,
                           List<Section> bottomSections) {}

    public record Settings(long version, String siteName, String intro, String avatarUrl,
                           List<Link> contacts, List<Link> accounts, List<Link> navigation,
                           List<Section> homeSections, Homepage homepage, String projectIntro,
                           List<SocialAccount> socialAccounts) {
        public Settings(long version, String siteName, String intro, String avatarUrl,
                        List<Link> contacts, List<Link> accounts, List<Link> navigation,
                        List<Section> homeSections) {
            this(version, siteName, intro, avatarUrl, contacts, accounts, navigation, homeSections, null, null, null);
        }

        public Settings(long version, String siteName, String intro, String avatarUrl,
                        List<Link> contacts, List<Link> accounts, List<Link> navigation,
                        List<Section> homeSections, Homepage homepage) {
            this(version, siteName, intro, avatarUrl, contacts, accounts, navigation, homeSections, homepage, null, null);
        }
    }
}
