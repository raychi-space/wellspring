package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import space.raychi.wellspring.dto.AdminArticle;
import space.raychi.wellspring.dto.ArticleInput;
import space.raychi.wellspring.dto.VersionInput;
import space.raychi.wellspring.entity.ContentRevisionEntity;
import space.raychi.wellspring.mapper.ContentRevisionMapper;
import space.raychi.wellspring.search.SearchState;
import space.raychi.wellspring.service.ArticleService;
import space.raychi.wellspring.service.AssetService;
import space.raychi.wellspring.service.ContentHistoryService;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:history;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "raychi.admin.username=test-admin",
    "raychi.assets.dir=./target/history-assets"
})
@AutoConfigureMockMvc
class ContentHistoryTest {
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("raychi.admin.password-hash", () -> new BCryptPasswordEncoder().encode("test-password"));
        String mysql = System.getenv("RAYCHI_TEST_PUBLICATION_MYSQL_URL");
        if (mysql != null && !mysql.isBlank()) {
            registry.add("spring.datasource.url", () -> mysql);
            registry.add("spring.datasource.username", () -> System.getenv("RAYCHI_TEST_MYSQL_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("RAYCHI_TEST_MYSQL_PASSWORD"));
        }
    }
    @Autowired ArticleService contents;
    @Autowired ContentHistoryService history;
    @Autowired AssetService assets;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @MockitoBean SearchState search;
    @MockitoSpyBean ContentRevisionMapper revisions;
    private final List<String> ids = new ArrayList<>();
    private final List<Path> files = new ArrayList<>();
    @AfterEach void cleanup() throws Exception {
        for (String id : ids) {
            if (db.queryForObject("SELECT COUNT(*) FROM articles WHERE id=?", Long.class, id) == 0) continue;
            var article = contents.getAdmin(id);
            contents.delete(id, new VersionInput(article.version()));
        }
        for (Path file : files) Files.deleteIfExists(file);
    }

    @Test void restoresHistoricalDraftWithoutChangingPublishedTextAddressDateOrOwnedImageVisibility() throws Exception {
        for (String type : List.of("ARTICLE", "POST")) {
            var article = create(type);
            String imageUrl = null;
            if (type.equals("ARTICLE")) {
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes);
                var image = assets.upload(article.id(), new MockMultipartFile("file", "x.png", "image/png", bytes.toByteArray()));
                files.add(Path.of("target/history-assets", image.id().replace("-", "")));
                imageUrl = image.url();
            }
            article = save(article, "旧工作稿", imageUrl);
            var oldRevision = history.list(article.id(), 1, 20).items().getFirst();
            article = contents.publish(article.id(), new VersionInput(article.version(), null, true));
            article = save(article, "当前公开版本", imageUrl);
            article = contents.publish(article.id(), new VersionInput(article.version(), null, true));
            var publicBefore = contents.getPublic(type, article.slug());
            article = save(article, "私有新工作稿", imageUrl);
            var restored = contents.restoreRevision(article.id(), oldRevision.id(), new VersionInput(article.version()));
            assertThat(restored.version()).isEqualTo(article.version() + 1);
            assertThat(restored.id()).isEqualTo(article.id());
            assertThat(restored.slug()).isEqualTo(article.slug());
            assertThat(restored.publishedAt()).isEqualTo(article.publishedAt());
            assertThat(restored.bodyMarkdown()).contains("旧工作稿").doesNotContain("私有新工作稿");
            assertThat(restored.hasUnpublishedChanges()).isTrue();
            assertThat(contents.getPublic(type, article.slug())).isEqualTo(publicBefore);
            assertThat(history.list(article.id(), 1, 20).items().getFirst().operation()).isEqualTo("RESTORE");
            if (imageUrl != null) {
                String imageId = imageUrl.split("/")[5];
                assertThat(assets.read(imageId, true).bytes()).isNotEmpty();
                assertThat(assets.read(imageId, false).bytes()).isNotEmpty();
            }
        }
    }

    @Test void existingContentRecordsOnlyKnownBaselineAndMutationAndFailureRollsBackHistoryAndPublication() {
        var article = save(create("ARTICLE"), "原有工作稿", null);
        db.update("DELETE FROM content_revisions WHERE article_id=?", article.id());
        var changed = save(article, "新工作稿", null);
        var known = history.list(article.id(), 1, 20);
        assertThat(known.total()).isEqualTo(2);
        assertThat(known.items()).extracting(item -> item.operation()).containsExactly("SAVE", "BASELINE");
        assertThat(history.get(article.id(), known.items().get(1).id()).snapshot().bodyMarkdown()).contains("原有工作稿");
        db.update("DELETE FROM content_revisions WHERE article_id=?", article.id());
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (invocation.<ContentRevisionEntity>getArgument(0).operation().equals("PUBLISH"))
                throw new IllegalStateException("late revision failure");
            return null;
        }).when(revisions).insert(any());
        assertThatThrownBy(() -> contents.publish(changed.id(), new VersionInput(changed.version(), null, true)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(contents.getAdmin(changed.id()).version()).isEqualTo(changed.version());
        assertThat(contents.getAdmin(changed.id()).status()).isEqualTo("DRAFT");
        assertThat(history.list(changed.id(), 1, 20).total()).isZero();
    }

    @Test void latest100RemainPagedAndPurgingContentCascadesAllRevisions() {
        var article = create("POST");
        String first = history.list(article.id(), 1, 20).items().getFirst().id();
        for (int i = 0; i < 105; i++) article = save(article, "历史" + i, null);
        var page = history.list(article.id(), 1, 20);
        assertThat(page.total()).isEqualTo(100);
        assertThat(page.items()).hasSize(20);
        assertThat(page.items().getFirst().articleVersion()).isEqualTo(article.version());
        assertThat(history.list(article.id(), 5, 20).items()).hasSize(20);
        assertThat(history.list(article.id(), 6, 20).items()).isEmpty();
        var current = article;
        assertThatThrownBy(() -> history.get(current.id(), first)).hasMessage("修订不存在或已超出保留范围。");
        contents.delete(current.id(), new VersionInput(current.version()));
        assertThat(revisions.count(current.id())).isZero();
    }

    @Test void httpRequiresAuthCsrfVersionAndCorrectParentAndListsNoPrivateBody() throws Exception {
        var article = save(create("POST"), "私密历史", null);
        var other = create("ARTICLE");
        var revision = history.list(article.id(), 1, 20).items().getFirst();
        String list = "/api/v1/admin/contents/" + article.id() + "/revisions";
        String detail = list + "/" + revision.id();
        mvc.perform(get(list)).andExpect(status().isUnauthorized());
        mvc.perform(get(list).with(user("owner"))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("X-Request-Id"))
            .andExpect(jsonPath("$.items[0].bodyMarkdown").doesNotExist())
            .andExpect(jsonPath("$.items[0].snapshot").doesNotExist());
        mvc.perform(get(detail).with(user("owner"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.snapshot.bodyMarkdown").value(article.bodyMarkdown()));
        mvc.perform(get("/api/v1/admin/contents/" + other.id() + "/revisions/" + revision.id()).with(user("owner")))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CONTENT_REVISION_NOT_FOUND"));
        mvc.perform(get(list + "?pageSize=51").with(user("owner"))).andExpect(status().isBadRequest());
        mvc.perform(post(detail + "/restore").with(user("owner")).contentType("application/json")
            .content("{\"expectedVersion\":" + article.version() + "}")).andExpect(status().isForbidden());
        mvc.perform(post(detail + "/restore").with(user("owner")).with(csrf()).contentType("application/json")
            .content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(detail + "/restore").with(user("owner")).with(csrf()).contentType("application/json")
            .content("{\"expectedVersion\":0}")).andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("ARTICLE_VERSION_CONFLICT"));
        mvc.perform(post(detail + "/restore").with(user("owner")).with(csrf()).contentType("application/json")
            .content("{\"expectedVersion\":" + article.version() + "}")).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"));
    }
    private AdminArticle create(String type) {
        var article = contents.create(type, null); ids.add(article.id()); return article;
    }
    private AdminArticle save(AdminArticle article, String title, String image) {
        return contents.save(article.id(), new ArticleInput(article.version(), article.slug(), title, title + "摘要",
            "# " + title + "\n\n正文" + (image == null ? "。" : "\n\n![图](" + image + ")"),
            List.of("history"), image, article.category(), true));
    }
}
