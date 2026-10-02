package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.dto.ArticleInput;
import space.raychi.wellspring.dto.NameDto;
import space.raychi.wellspring.dto.VersionInput;
import space.raychi.wellspring.service.ArticleService;
import space.raychi.wellspring.service.AssetService;
import space.raychi.wellspring.service.TaxonomyService;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:raychi;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "raychi.admin.username=test-admin",
        "raychi.assets.dir=./target/test-assets"
})
class PublishingFlowTest {
    @DynamicPropertySource
    static void password(DynamicPropertyRegistry registry) {
        registry.add("raychi.admin.password-hash", () -> new BCryptPasswordEncoder().encode("test-password"));
    }

    @Autowired ArticleService articles;
    @Autowired AssetService assets;
    @Autowired TaxonomyService taxonomy;

    @Test
    void draftImagesBecomePublicOnlyWithThePublishedSnapshot() throws Exception {
        taxonomy.createTag(new NameDto("记录"));
        var draft = articles.create(null);
        byte[] png = image();
        var first = assets.upload(draft.id(), new MockMultipartFile("file", "one.png", "image/png", png));
        assertNotFound(() -> assets.read(first.id(), false));
        assertThat(assets.read(first.id(), true).bytes()).isEqualTo(png);

        var saved = articles.save(draft.id(), new ArticleInput(draft.version(),
                "hello-" + UUID.randomUUID(), "第一版", "原摘要", imageMarkdown(first.url()), List.of("记录"), null));
        assertNotFound(() -> articles.getPublic(saved.slug()));
        assertNotFound(() -> assets.read(first.id(), false));

        var published = articles.publish(draft.id(), new VersionInput(saved.version()));
        assertThat(articles.getPublic(saved.slug()).title()).isEqualTo("第一版");
        assertThat(assets.read(first.id(), false).bytes()).isEqualTo(png);

        var second = assets.upload(draft.id(), new MockMultipartFile("file", "two.png", "image/png", png));
        var changed = articles.save(draft.id(), new ArticleInput(published.version(),
                saved.slug(), "第二版", "新摘要", imageMarkdown(second.url()), List.of(), null));
        assertThat(changed.hasUnpublishedChanges()).isTrue();
        assertThat(articles.getPublic(saved.slug()).title()).isEqualTo("第一版");
        assertNotFound(() -> assets.read(second.id(), false));

        var republished = articles.publish(draft.id(), new VersionInput(changed.version()));
        assertThat(articles.getPublic(saved.slug()).title()).isEqualTo("第二版");
        assertNotFound(() -> assets.read(first.id(), false));
        assertThat(assets.read(second.id(), false).bytes()).isEqualTo(png);

        articles.unpublish(draft.id(), new VersionInput(republished.version()));
        assertNotFound(() -> articles.getPublic(saved.slug()));
        assertNotFound(() -> assets.read(second.id(), false));
    }

    @Test
    void staleWorkingCopyCannotOverwriteNewerChanges() {
        var draft = articles.create(null);
        var input = new ArticleInput(draft.version(), "draft-locked", "标题", "", "正文", List.of(), null);
        articles.save(draft.id(), input);
        assertThatThrownBy(() -> articles.save(draft.id(), input))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.status()).isEqualTo(HttpStatus.CONFLICT));
    }

    private static String imageMarkdown(String url) { return "# 正文\n\n![图片](" + url + ")"; }

    private static byte[] image() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private static void assertNotFound(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiException.class,
                ex -> assertThat(ex.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }
}
