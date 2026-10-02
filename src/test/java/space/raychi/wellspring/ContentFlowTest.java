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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.dto.AdminArticle;
import space.raychi.wellspring.dto.ArticleInput;
import space.raychi.wellspring.dto.NameDto;
import space.raychi.wellspring.dto.PublicArticle;
import space.raychi.wellspring.dto.SiteSettingsDto;
import space.raychi.wellspring.dto.VersionInput;
import space.raychi.wellspring.service.ArticleService;
import space.raychi.wellspring.service.AssetService;
import space.raychi.wellspring.service.SiteSettingsService;
import space.raychi.wellspring.service.TaxonomyService;

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
    @Autowired TaxonomyService taxonomy;
    @Autowired SiteSettingsService settings;
    @Autowired AssetService assets;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void databasePaginationKeepsCaseDistinctTagsAndPublishedSnapshot() {
        String tag = "Page" + java.util.UUID.randomUUID().toString().substring(0, 8);
        String lower = tag.toLowerCase();
        taxonomy.createTag(new NameDto(tag));
        taxonomy.createTag(new NameDto(lower));
        var ids = new java.util.ArrayList<String>();
        try {
            for (int i = 0; i < 55; i++) {
                var item = contents.create("POST", null);
                ids.add(item.id());
                item = contents.save(item.id(), new ArticleInput(item.version(), null, String.format("Page %02d", i), "",
                        "Published body", List.of(tag), null, null));
                contents.publish(item.id(), new VersionInput(item.version()));
            }
            var first = contents.listPublic(1, 50, "POST", null, tag, "title");
            var second = contents.listPublic(2, 50, "POST", null, tag, "title");
            assertThat(first.total()).isEqualTo(55);
            assertThat(first.items()).hasSize(50);
            assertThat(second.items()).hasSize(5);
            var adminPage = contents.listAdmin(2, 50, "PUBLISHED", "POST", null, tag, "oldest");
            assertThat(adminPage.total()).isEqualTo(55);
            assertThat(adminPage.items()).hasSize(5);
            assertThat(contents.listAdmin(1, 50, "PUBLISHED", "POST", null, lower, "recent").total()).isZero();
            assertThat(first.items().get(0).title()).isEqualTo("Page 00");
            assertThat(second.items().get(0).title()).isEqualTo("Page 50");
            assertThat(contents.listPublic(1, 50, "POST", null, lower).total()).isZero();
            var item = contents.getAdmin(ids.get(0));
            item = contents.save(item.id(), new ArticleInput(item.version(), null, item.title(), "", "New draft", List.of(lower), null, null));
            assertThat(contents.listPublic(1, 50, "POST", null, tag).total()).isEqualTo(55);
            assertThat(contents.listPublic(1, 50, "POST", null, lower).total()).isZero();
            assertThat(contents.listAdmin(1, 50, "PUBLISHED", "POST", null, lower, "recent").total()).isEqualTo(1);
            contents.publish(item.id(), new VersionInput(item.version()));
            assertThat(contents.listPublic(1, 50, "POST", null, tag).total()).isEqualTo(54);
            assertThat(contents.listPublic(1, 50, "POST", null, lower).total()).isEqualTo(1);
            assertThat(contents.listPublic(3, 50, "POST", null, tag).items()).isEmpty();
        } finally {
            for (String id : ids) contents.delete(id, new VersionInput(contents.getAdmin(id).version()));
        }
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired space.raychi.wellspring.service.AssetCleanupWorker cleanup;

    @Test
    void assetCleanupWaitsForCommitAndRetainsFailedFilesForRetry() throws Exception {
        var item = contents.create("ARTICLE", null);
        var uploaded = assets.upload(item.id(), new MockMultipartFile("file", "pixel.png", "image/png",
                java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j4WQAAAAASUVORK5CYII=")));
        String key = uploaded.id().replace("-", "");
        var path = java.nio.file.Path.of("target/test-assets", key);
        var tx = new org.springframework.transaction.support.TransactionTemplate(transactions);
        tx.executeWithoutResult(status -> {
            contents.delete(item.id(), new VersionInput(contents.getAdmin(item.id()).version()));
            status.setRollbackOnly();
        });
        cleanup.clean();
        assertThat(java.nio.file.Files.exists(path)).isTrue();
        assertThat(contents.getAdmin(item.id())).isNotNull();
        contents.delete(item.id(), new VersionInput(contents.getAdmin(item.id()).version()));
        // A transient filesystem failure keeps the durable queue entry for another pass.
        java.nio.file.Files.delete(path);
        java.nio.file.Files.createDirectory(path);
        java.nio.file.Files.writeString(path.resolve("busy"), "test");
        cleanup.clean();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM asset_deletion_queue WHERE storage_key=?", Integer.class, key)).isEqualTo(1);
        java.nio.file.Files.delete(path.resolve("busy"));
        java.nio.file.Files.delete(path);
        java.nio.file.Files.write(path, new byte[] {1});
        cleanup.clean();
        assertThat(java.nio.file.Files.exists(path)).isFalse();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM asset_deletion_queue WHERE storage_key=?", Integer.class, key)).isZero();
    }

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
        taxonomy.createTag(new NameDto("随笔"));
        var post = contents.create("POST", null);
        var savedPost = contents.save(post.id(), new ArticleInput(post.version(), null, "", "",
                "第一版", List.of("随笔"), null, null));
        assertThatThrownBy(() -> contents.getPublic("POST", post.slug())).isInstanceOf(ApiException.class);
        var publishedPost = contents.publish(post.id(), new VersionInput(savedPost.version()));
        assertThat(contents.getPublic("POST", post.slug()).bodyMarkdown()).isEqualTo("第一版");

        var revised = contents.save(post.id(), new ArticleInput(publishedPost.version(), null, "", "",
                "私人修改", List.of("随笔"), null, null));
        assertThat(contents.getPublic("POST", post.slug()).bodyMarkdown()).isEqualTo("第一版");
        assertThat(contents.listPublic(1, 10, null, null, "随笔").items()).hasSize(1);
        contents.publish(post.id(), new VersionInput(revised.version()));
        assertThat(contents.getPublic("POST", post.slug()).bodyMarkdown()).isEqualTo("私人修改");

        var hidden = contents.unpublish(post.id(), new VersionInput(revised.version() + 1));
        assertThat(hidden.status()).isEqualTo("DRAFT");
        assertThatThrownBy(() -> contents.getPublic("POST", post.slug()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void contentTypeRulesAndSettingsAreIndependent() {
        assertThatThrownBy(() -> contents.create("THOUGHT", null)).isInstanceOf(ApiException.class);
        var post = contents.create("POST", null);
        assertThatThrownBy(() -> contents.save(post.id(), new ArticleInput(post.version(), null,
                "", "", "![x](https://example.com/x.png)", List.of(), null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> assets.upload(post.id(), new MockMultipartFile("file", "x.png", "image/png", new byte[]{1})))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        var initial = settings.publicSettings();
        var saved = settings.save(new SiteSettingsDto.Settings(initial.version(), "新站名", "新介绍", null,
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
        assertThat(contents.listAdmin(1, 50, "DRAFT", "POST").items()).extracting(AdminArticle::id)
                .contains(legacy.id());
        var saved = contents.save(legacy.id(), new ArticleInput(legacy.version(), null, "", "",
                "旧想法正文", List.of(), null, null));
        assertThat(saved.type()).isEqualTo("POST");
        assertThat(db.queryForObject("SELECT content_type FROM articles WHERE id=?", String.class, legacy.id())).isEqualTo("POST");
        contents.publish(legacy.id(), new VersionInput(saved.version()));
        assertThat(contents.getPublic("POST", legacy.slug()).bodyMarkdown()).isEqualTo("旧想法正文");
        db.update("UPDATE articles SET content_type='THOUGHT', draft_category='未分类', public_category='未分类' WHERE id=?", legacy.id());
        assertThat(contents.getPublic("POST", legacy.slug()).type()).isEqualTo("POST");
        assertThat(contents.getPublic("POST", legacy.slug()).category()).isNull();
        assertThat(contents.listPublic(1, 50, "POST", null, null).items())
                .extracting(PublicArticle::id).contains(legacy.id());
    }

    @Test
    void homepageSettingsCanBeEditedAndRejectInvalidProjects() {
        var initial = settings.publicSettings();
        assertThat(initial.homepage().recentSections()).hasSize(3);
        assertThat(initial.projectIntro()).isNotBlank();
        var homepage = new SiteSettingsDto.Homepage("正在做的事", List.of(
                new SiteSettingsDto.Project("Raychi", "个人网站", "进行中", "https://github.com/raychi-space/raychi")),
                List.of(new SiteSettingsDto.Section("posts", true),
                        new SiteSettingsDto.Section("featured", false),
                        new SiteSettingsDto.Section("writing", true)),
                List.of(new SiteSettingsDto.Section("stats", true),
                        new SiteSettingsDto.Section("projects", true)));
        var socialAccounts = List.of(new SiteSettingsDto.SocialAccount("github", true,
                "https://github.com/raychi-space"));
        var saved = settings.save(new SiteSettingsDto.Settings(initial.version(), initial.siteName(),
                initial.intro(), initial.avatarUrl(), initial.contacts(), initial.accounts(), initial.navigation(),
                initial.homeSections(), homepage, "最近的工作", socialAccounts));
        assertThat(settings.publicSettings().homepage()).isEqualTo(homepage);
        assertThat(settings.publicSettings().projectIntro()).isEqualTo("最近的工作");
        assertThat(settings.publicSettings().socialAccounts()).isEqualTo(socialAccounts);
        var legacySaved = settings.save(new SiteSettingsDto.Settings(saved.version(), saved.siteName(),
                "旧客户端更新", saved.avatarUrl(), saved.contacts(), saved.accounts(), saved.navigation(),
                saved.homeSections()));
        assertThat(legacySaved.homepage()).isEqualTo(homepage);
        assertThat(legacySaved.socialAccounts()).isEqualTo(socialAccounts);
        assertThatThrownBy(() -> settings.save(new SiteSettingsDto.Settings(legacySaved.version(), legacySaved.siteName(),
                legacySaved.intro(), legacySaved.avatarUrl(), legacySaved.contacts(), legacySaved.accounts(), legacySaved.navigation(),
                legacySaved.homeSections(), new SiteSettingsDto.Homepage("", List.of(
                new SiteSettingsDto.Project("Bad", "", "进行中", "javascript:alert(1)")),
                homepage.recentSections(), homepage.bottomSections())))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> settings.save(new SiteSettingsDto.Settings(legacySaved.version(), legacySaved.siteName(),
                legacySaved.intro(), legacySaved.avatarUrl(), legacySaved.contacts(), legacySaved.accounts(), legacySaved.navigation(),
                legacySaved.homeSections(), homepage, "", List.of(new SiteSettingsDto.SocialAccount(
                "github", true, "javascript:alert(1)"))))).isInstanceOf(ApiException.class);
    }

    @Test
    void legacyPlatformLinkAppearsAsIconAccount() {
        var initial = settings.publicSettings();
        var saved = settings.save(new SiteSettingsDto.Settings(initial.version(), initial.siteName(), initial.intro(),
                initial.avatarUrl(), initial.contacts(), List.of(new SiteSettingsDto.Link("GitHub", "https://github.com/example")),
                initial.navigation(), initial.homeSections(), initial.homepage(), initial.projectIntro(), List.of()));
        assertThat(saved.accounts()).isEmpty();
        assertThat(saved.socialAccounts()).contains(new SiteSettingsDto.SocialAccount(
                "github", true, "https://github.com/example"));
        settings.save(saved);
        assertThat(settings.publicSettings().socialAccounts()).contains(new SiteSettingsDto.SocialAccount(
                "github", true, "https://github.com/example"));
    }
}
