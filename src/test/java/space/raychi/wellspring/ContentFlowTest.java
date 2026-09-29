package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.article.ArticleService;
import space.raychi.wellspring.article.TaxonomyController;
import space.raychi.wellspring.asset.AssetService;
import space.raychi.wellspring.site.SiteSettingsController;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:content;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "raychi.admin.username=test-admin", "raychi.assets.dir=./target/test-assets"
})
@AutoConfigureMockMvc
class ContentFlowTest {
    @DynamicPropertySource
    static void password(DynamicPropertyRegistry registry) {
        registry.add("raychi.admin.password-hash", () -> new BCryptPasswordEncoder().encode("test-password"));
    }

    @Autowired ArticleService contents;
    @Autowired TaxonomyController taxonomy;
    @Autowired SiteSettingsController settings;
    @Autowired AssetService assets;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    @WithMockUser
    void staleSettingsSaveReturnsConflictOverHttp() throws Exception {
        String staleInput = json.writeValueAsString(settings.adminSettings());
        mvc.perform(put("/api/v1/admin/settings").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(staleInput)).andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/settings").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(staleInput)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SETTINGS_VERSION_CONFLICT"));
    }

    @Test
    @WithMockUser
    void legacyArticleEndpointsRejectPostAndThoughtIds() throws Exception {
        for (String type : List.of("POST", "THOUGHT")) {
            var content = contents.create("POST", null);
            if ("THOUGHT".equals(type))
                db.update("UPDATE articles SET content_type='THOUGHT' WHERE id=?", content.id());
            String path = "/api/v1/admin/articles/" + content.id();
            mvc.perform(get("/api/v1/admin/contents/{id}", content.id())).andExpect(status().isOk());
            mvc.perform(get(path)).andExpect(status().isNotFound());
            mvc.perform(put(path).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"bodyMarkdown\":\"不应写入\"}"))
                    .andExpect(status().isNotFound());
            mvc.perform(post(path + "/publish").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedVersion\":0}"))
                    .andExpect(status().isNotFound());
            mvc.perform(post(path + "/unpublish").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expectedVersion\":0}"))
                    .andExpect(status().isNotFound());
            assertThat(contents.getAdmin(content.id()).version()).isZero();
            assertThat(contents.getAdmin(content.id()).status()).isEqualTo("DRAFT");
        }
    }

    @Test
    void untitledPostKeepsPublishedSnapshotsAndStableLinks() {
        taxonomy.createTag(new TaxonomyController.Name("随笔"));
        var post = contents.create("POST", null);
        var savedPost = contents.save(post.id(), new ArticleService.ArticleInput(post.version(), null, "", "",
                "第一版", List.of("随笔"), null, null));
        assertThatThrownBy(() -> contents.getPublic("POST", post.slug())).isInstanceOf(ApiException.class);
        var publishedPost = contents.publish(post.id(), new ArticleService.VersionInput(savedPost.version()));
        assertThat(contents.getPublic("POST", post.slug()).bodyMarkdown()).isEqualTo("第一版");

        var revised = contents.save(post.id(), new ArticleService.ArticleInput(publishedPost.version(), null, "", "",
                "私人修改", List.of("随笔"), null, null));
        assertThat(contents.getPublic("POST", post.slug()).bodyMarkdown()).isEqualTo("第一版");
        assertThat(contents.listPublic(1, 10, null, null, "随笔").items()).hasSize(1);
        contents.publish(post.id(), new ArticleService.VersionInput(revised.version()));
        assertThat(contents.getPublic("POST", post.slug()).bodyMarkdown()).isEqualTo("私人修改");

        var hidden = contents.unpublish(post.id(), new ArticleService.VersionInput(revised.version() + 1));
        assertThat(hidden.status()).isEqualTo("DRAFT");
        assertThatThrownBy(() -> contents.getPublic("POST", post.slug()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void contentTypeRulesAndSettingsAreIndependent() {
        assertThatThrownBy(() -> contents.create("THOUGHT", null)).isInstanceOf(ApiException.class);
        var post = contents.create("POST", null);
        assertThatThrownBy(() -> contents.save(post.id(), new ArticleService.ArticleInput(post.version(), null,
                "", "", "![x](https://example.com/x.png)", List.of(), null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> assets.upload(post.id(), new MockMultipartFile("file", "x.png", "image/png", new byte[]{1})))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        var initial = settings.publicSettings();
        var saved = settings.save(new SiteSettingsController.Settings(initial.version(), "新站名", "新介绍", null,
                List.of(), List.of(), initial.navigation(), initial.homeSections()));
        assertThat(settings.publicSettings().siteName()).isEqualTo("新站名");
        assertThat(saved.version()).isEqualTo(initial.version() + 1);
        assertThat(saved.homepage()).isEqualTo(initial.homepage());
        assertThat(contents.getAdmin(post.id()).status()).isEqualTo("DRAFT");
    }

    @Test
    void existingThoughtAppearsAsPostAndConvertsWhenEdited() {
        var legacy = contents.create("POST", null);
        db.update("UPDATE articles SET content_type='THOUGHT', draft_category='未分类' WHERE id=?", legacy.id());
        assertThat(contents.getAdmin(legacy.id()).type()).isEqualTo("POST");
        assertThat(contents.getAdmin(legacy.id()).category()).isNull();
        assertThat(contents.listAdmin(1, 50, "DRAFT", "POST").items()).extracting(ArticleService.AdminArticle::id)
                .contains(legacy.id());
        var saved = contents.save(legacy.id(), new ArticleService.ArticleInput(legacy.version(), null, "", "",
                "旧想法正文", List.of(), null, null));
        assertThat(saved.type()).isEqualTo("POST");
        assertThat(db.queryForObject("SELECT content_type FROM articles WHERE id=?", String.class, legacy.id())).isEqualTo("POST");
        contents.publish(legacy.id(), new ArticleService.VersionInput(saved.version()));
        assertThat(contents.getPublic("POST", legacy.slug()).bodyMarkdown()).isEqualTo("旧想法正文");
        db.update("UPDATE articles SET content_type='THOUGHT', draft_category='未分类', public_category='未分类' WHERE id=?", legacy.id());
        assertThat(contents.getPublic("POST", legacy.slug()).type()).isEqualTo("POST");
        assertThat(contents.getPublic("POST", legacy.slug()).category()).isNull();
        assertThat(contents.listPublic(1, 50, "POST", null, null).items())
                .extracting(ArticleService.PublicArticle::id).contains(legacy.id());
    }

    @Test
    void homepageSettingsCanBeEditedAndRejectInvalidProjects() {
        var initial = settings.publicSettings();
        assertThat(initial.homepage().recentSections()).hasSize(3);
        assertThat(initial.projectIntro()).isNotBlank();
        var homepage = new SiteSettingsController.Homepage("正在做的事", List.of(
                new SiteSettingsController.Project("Raychi", "个人网站", "进行中", "https://github.com/raychi-space/raychi")),
                List.of(new SiteSettingsController.Section("posts", true),
                        new SiteSettingsController.Section("featured", false),
                        new SiteSettingsController.Section("writing", true)),
                List.of(new SiteSettingsController.Section("stats", true),
                        new SiteSettingsController.Section("projects", true)));
        var socialAccounts = List.of(new SiteSettingsController.SocialAccount("github", true,
                "https://github.com/raychi-space"));
        var saved = settings.save(new SiteSettingsController.Settings(initial.version(), initial.siteName(),
                initial.intro(), initial.avatarUrl(), initial.contacts(), initial.accounts(), initial.navigation(),
                initial.homeSections(), homepage, "最近的工作", socialAccounts));
        assertThat(settings.publicSettings().homepage()).isEqualTo(homepage);
        assertThat(settings.publicSettings().projectIntro()).isEqualTo("最近的工作");
        assertThat(settings.publicSettings().socialAccounts()).isEqualTo(socialAccounts);
        var legacySaved = settings.save(new SiteSettingsController.Settings(saved.version(), saved.siteName(),
                "旧客户端更新", saved.avatarUrl(), saved.contacts(), saved.accounts(), saved.navigation(),
                saved.homeSections()));
        assertThat(legacySaved.homepage()).isEqualTo(homepage);
        assertThat(legacySaved.socialAccounts()).isEqualTo(socialAccounts);
        assertThatThrownBy(() -> settings.save(new SiteSettingsController.Settings(legacySaved.version(), legacySaved.siteName(),
                legacySaved.intro(), legacySaved.avatarUrl(), legacySaved.contacts(), legacySaved.accounts(), legacySaved.navigation(),
                legacySaved.homeSections(), new SiteSettingsController.Homepage("", List.of(
                new SiteSettingsController.Project("Bad", "", "进行中", "javascript:alert(1)")),
                homepage.recentSections(), homepage.bottomSections())))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> settings.save(new SiteSettingsController.Settings(legacySaved.version(), legacySaved.siteName(),
                legacySaved.intro(), legacySaved.avatarUrl(), legacySaved.contacts(), legacySaved.accounts(), legacySaved.navigation(),
                legacySaved.homeSections(), homepage, "", List.of(new SiteSettingsController.SocialAccount(
                "github", true, "javascript:alert(1)"))))).isInstanceOf(ApiException.class);
    }

    @Test
    void legacyPlatformLinkAppearsAsIconAccount() {
        var initial = settings.publicSettings();
        var saved = settings.save(new SiteSettingsController.Settings(initial.version(), initial.siteName(), initial.intro(),
                initial.avatarUrl(), initial.contacts(), List.of(new SiteSettingsController.Link("GitHub", "https://github.com/example")),
                initial.navigation(), initial.homeSections(), initial.homepage(), initial.projectIntro(), List.of()));
        assertThat(saved.accounts()).isEmpty();
        assertThat(saved.socialAccounts()).contains(new SiteSettingsController.SocialAccount(
                "github", true, "https://github.com/example"));
        settings.save(saved);
        assertThat(settings.publicSettings().socialAccounts()).contains(new SiteSettingsController.SocialAccount(
                "github", true, "https://github.com/example"));
    }
}
