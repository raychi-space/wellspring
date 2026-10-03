package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import space.raychi.wellspring.dto.ArticleInput;
import space.raychi.wellspring.dto.NameDto;
import space.raychi.wellspring.dto.VersionInput;
import space.raychi.wellspring.service.ArticleService;
import space.raychi.wellspring.service.TaxonomyService;
import space.raychi.wellspring.search.SearchState;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:deferredtags;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "raychi.admin.username=test-admin",
    "raychi.admin.password-hash=$2a$10$fixtureFixtureFixtureFixtureFixtureFixtureFixtureFixture"
})
class DeferredTagsTest {
    @org.springframework.test.context.DynamicPropertySource
    static void credentials(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("raychi.admin.password-hash", () -> new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("test-password"));
    }
    @Autowired ArticleService contents;
    @Autowired TaxonomyService taxonomy;
    @MockitoBean SearchState search;

    @Test
    void draftNamesStayPrivateAndLatePublicationFailureRollsBackCatalog() {
        String tag = "new-" + UUID.randomUUID().toString().substring(0, 8);
        for (String type : List.of("POST", "ARTICLE")) {
            String name = tag + type;
            var draft = contents.create(type, null);
            var saved = contents.save(draft.id(), new ArticleInput(draft.version(), draft.slug(), "标题", "摘要",
                    "# 标题\n\n正文", List.of(name), null, null));
            assertThat(saved.tags()).containsExactly(name);
            assertThat(taxonomy.tags()).extracting(NameDto::name).doesNotContain(name);
            assertThatThrownBy(() -> contents.publish(saved.id(), new VersionInput(saved.version() - 1)))
                    .isInstanceOf(RuntimeException.class);
            assertThat(taxonomy.tags()).extracting(NameDto::name).doesNotContain(name);
            doThrow(new IllegalStateException("late publication failure")).when(search).capture(saved.id(), false);
            assertThatThrownBy(() -> contents.publish(saved.id(), new VersionInput(saved.version(), null, true)))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(taxonomy.tags()).extracting(NameDto::name).doesNotContain(name);
            assertThat(contents.getAdmin(saved.id()).status()).isEqualTo("DRAFT");
            assertThat(contents.getAdmin(saved.id()).version()).isEqualTo(saved.version());
            doNothing().when(search).capture(saved.id(), false);
            var published = contents.publish(saved.id(), new VersionInput(saved.version(), null, true));
            assertThat(taxonomy.tags()).extracting(NameDto::name).contains(name);
            assertThat(contents.getPublic(type, published.slug()).tags()).containsExactly(name);
            contents.publish(published.id(), new VersionInput(published.version(), null, true));
            assertThat(taxonomy.tags().stream().filter(t -> t.name().equals(name)).count()).isEqualTo(1);
        }
    }
}
