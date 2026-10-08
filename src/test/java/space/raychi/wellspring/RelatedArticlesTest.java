package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import space.raychi.wellspring.dto.AdminArticle;
import space.raychi.wellspring.dto.ArticleInput;
import space.raychi.wellspring.dto.NameDto;
import space.raychi.wellspring.dto.RelatedArticle;
import space.raychi.wellspring.dto.VersionInput;
import space.raychi.wellspring.search.SearchState;
import space.raychi.wellspring.service.ArticleService;
import space.raychi.wellspring.service.RelatedArticlesService;
import space.raychi.wellspring.service.TaxonomyService;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:related;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "raychi.admin.username=test-admin"
})
@AutoConfigureMockMvc
@Transactional
class RelatedArticlesTest {
    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry registry) {
        registry.add("raychi.admin.password-hash", () -> new BCryptPasswordEncoder().encode("test-password"));
        String mysql = System.getenv("RAYCHI_TEST_PUBLICATION_MYSQL_URL");
        if (mysql != null && !mysql.isBlank()) {
            registry.add("spring.datasource.url", () -> mysql);
            registry.add("spring.datasource.username", () -> System.getenv("RAYCHI_TEST_MYSQL_USER"));
            registry.add("spring.datasource.password", () -> System.getenv("RAYCHI_TEST_MYSQL_PASSWORD"));
        }
    }
    @Autowired ArticleService contents;
    @Autowired RelatedArticlesService related;
    @Autowired TaxonomyService taxonomy;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @MockitoBean SearchState search;

    @Test
    void scoresExactPublishedTagsBeforeCategoryAndUsesStableDateIdTies() {
        String prefix = UUID.randomUUID().toString().substring(0, 8);
        String category = "related-" + prefix;
        taxonomy.createCategory(new NameDto(category));
        String tag = prefix + "-中文'quoted";
        var target = published("ARTICLE", List.of(tag, prefix + "-Case"), category);
        var both = published("ARTICLE", target.tags(), null);
        var oneAndCategory = published("ARTICLE", List.of(tag), category);
        var tieOne = published("ARTICLE", List.of(tag), null);
        var tieTwo = published("ARTICLE", List.of(tag), null);
        var categoryOnly = published("ARTICLE", List.of(prefix + "-case"), category);
        published("ARTICLE", List.of(prefix + "-case"), null); // Exact case, no fallback.
        published("POST", List.of(tag), null);
        var withdrawn = published("ARTICLE", List.of(tag), null);
        contents.unpublish(withdrawn.id(), new VersionInput(withdrawn.version()));
        var draft = contents.create("ARTICLE", null);
        contents.save(draft.id(), input(draft, List.of(tag), category));
        Timestamp tieDate = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
        db.update("UPDATE articles SET published_at=? WHERE id IN (?,?)", tieDate, tieOne.id(), tieTwo.id());
        var ties = List.of(tieOne.id(), tieTwo.id()).stream().sorted(java.util.Comparator.reverseOrder()).toList();
        assertThat(related.articles(target.slug(), 6)).extracting(RelatedArticle::id)
            .containsExactly(both.id(), oneAndCategory.id(), ties.get(0), ties.get(1), categoryOnly.id());
        assertThat(related.articles(target.slug(), 1)).extracting(RelatedArticle::id).containsExactly(both.id());
    }

    @Test
    void workingEditsStayPrivateUntilRepublishAndWithdrawalRemovesMatches() {
        String tag = "related-" + UUID.randomUUID().toString().substring(0, 8);
        var target = published("ARTICLE", List.of(tag), null);
        var candidate = published("ARTICLE", List.of(tag), null);
        var saved = contents.save(candidate.id(), input(candidate, List.of("changed-" + UUID.randomUUID().toString().substring(0, 8)), null));
        assertThat(related.articles(target.slug(), 3)).extracting(RelatedArticle::title).containsExactly("公开标题");
        var targetDraft = contents.save(target.id(), input(target, List.of("private-" + UUID.randomUUID().toString().substring(0, 8)), null));
        assertThat(related.articles(target.slug(), 3)).extracting(RelatedArticle::id).containsExactly(candidate.id());
        var republished = contents.publish(saved.id(), new VersionInput(saved.version(), null, true));
        assertThat(related.articles(target.slug(), 3)).isEmpty();
        var restored = contents.save(republished.id(), input(republished, List.of(tag), null));
        restored = contents.publish(restored.id(), new VersionInput(restored.version(), null, true));
        assertThat(related.articles(target.slug(), 3)).hasSize(1);
        contents.unpublish(restored.id(), new VersionInput(restored.version()));
        assertThat(related.articles(target.slug(), 3)).isEmpty();
        contents.unpublish(targetDraft.id(), new VersionInput(targetDraft.version()));
        assertThatThrownBy(() -> related.articles(target.slug(), 3)).hasMessage("内容不存在。");
    }

    @Test
    void anonymousHttpLimitsMetadataNoStoreAndPrivateTargetErrors() throws Exception {
        String tag = "related-" + UUID.randomUUID().toString().substring(0, 8);
        var target = published("ARTICLE", List.of(tag), null);
        for (int i = 0; i < 7; i++) published("ARTICLE", List.of(tag), null);
        String endpoint = "/api/v1/public/contents/ARTICLE/" + target.slug() + "/related";
        mvc.perform(get(endpoint)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().exists("X-Request-Id"))
            .andExpect(jsonPath("$.length()").value(3))
            .andExpect(jsonPath("$[0].title").value("公开标题"))
            .andExpect(jsonPath("$[0].bodyMarkdown").doesNotExist())
            .andExpect(jsonPath("$[0].tags").doesNotExist())
            .andExpect(jsonPath("$[0].version").doesNotExist())
            .andExpect(jsonPath("$[0].relevance").doesNotExist());
        mvc.perform(get(endpoint).param("limit", "6")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6));
        for (String limit : List.of("0", "7", "bad"))
            mvc.perform(get(endpoint).param("limit", limit)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        var empty = published("ARTICLE", List.of(), null);
        mvc.perform(get("/api/v1/public/contents/ARTICLE/" + empty.slug() + "/related"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        var draft = contents.create("ARTICLE", null);
        for (String slug : List.of("missing", draft.slug()))
            mvc.perform(get("/api/v1/public/contents/ARTICLE/" + slug + "/related"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ARTICLE_NOT_FOUND"));
        mvc.perform(get("/api/v1/public/contents/POST/" + target.slug() + "/related"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    private AdminArticle published(String type, List<String> tags, String category) {
        var draft = contents.create(type, null);
        var saved = contents.save(draft.id(), new ArticleInput(draft.version(), draft.slug(), "公开标题", "公开摘要",
            "# 公开标题\n\n正文", tags, null, category, true));
        return contents.publish(saved.id(), new VersionInput(saved.version(), null, true));
    }

    private ArticleInput input(AdminArticle content, List<String> tags, String category) {
        return new ArticleInput(content.version(), content.slug(), "私有工作标题", "私有工作摘要",
            "# 私有工作标题\n\n工作正文", tags, null, category, true);
    }
}
