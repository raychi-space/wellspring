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
            var content = contents.create(type, null);
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
    void untitledPostAndThoughtKeepPublishedSnapshotsAndStableLinks() {
        taxonomy.createCategory(new TaxonomyController.Name("技术"));
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

        var thought = contents.create("THOUGHT", null);
        assertThatThrownBy(() -> assets.upload(thought.id(), new MockMultipartFile("file", "x.png", "image/png", new byte[]{1})))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST));
        var savedThought = contents.save(thought.id(), new ArticleService.ArticleInput(thought.version(), null, "", "",
                "关于一件事", List.of(), null, "技术"));
        contents.publish(thought.id(), new ArticleService.VersionInput(savedThought.version()));
        assertThat(contents.getPublic("THOUGHT", thought.slug()).category()).isEqualTo("技术");
        var compatibilitySave = contents.save(thought.id(), new ArticleService.ArticleInput(savedThought.version() + 1,
                thought.slug(), "", "", "旧客户端保存", List.of(), null));
        assertThat(compatibilitySave.category()).isEqualTo("技术");
        assertThat(contents.getPublic("THOUGHT", thought.slug()).bodyMarkdown()).isEqualTo("关于一件事");
        assertThat(contents.listPublic(1, 10, null, "技术", null).items()).hasSize(1);
        assertThat(contents.listPublic(1, 10, null, null, null).items()).hasSize(2);

        var hidden = contents.unpublish(post.id(), new ArticleService.VersionInput(revised.version() + 1));
        assertThat(hidden.status()).isEqualTo("DRAFT");
        assertThatThrownBy(() -> contents.getPublic("POST", post.slug()))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void contentTypeRulesAndSettingsAreIndependent() {
        var thought = contents.create("THOUGHT", null);
        assertThatThrownBy(() -> contents.save(thought.id(), new ArticleService.ArticleInput(thought.version(), null,
                "", "", "![x](https://example.com/x.png)", List.of(), null, null)))
                .isInstanceOf(ApiException.class);
        var initial = settings.publicSettings();
        var saved = settings.save(new SiteSettingsController.Settings(initial.version(), "新站名", "新介绍", null,
                List.of(), List.of(), initial.navigation(), initial.homeSections()));
        assertThat(settings.publicSettings().siteName()).isEqualTo("新站名");
        assertThat(saved.version()).isEqualTo(initial.version() + 1);
        assertThat(contents.getAdmin(thought.id()).status()).isEqualTo("DRAFT");
    }
}
