package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import space.raychi.wellspring.dto.ArticleInput;
import space.raychi.wellspring.dto.VersionInput;
import space.raychi.wellspring.search.SearchState;
import space.raychi.wellspring.service.ArticleService;
import space.raychi.wellspring.service.AssetService;
import space.raychi.wellspring.service.ContentExportService;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:contentexport;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "raychi.admin.username=test-admin",
    "raychi.assets.dir=./target/export-assets"
})
@AutoConfigureMockMvc
@Transactional
class ContentExportTest {
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
    @Autowired ContentExportService exports;
    @Autowired AssetService assets;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @MockitoBean SearchState search;
    private final List<Path> createdFiles = new ArrayList<>();
    @AfterEach void cleanup() throws Exception { for (var path : createdFiles) Files.deleteIfExists(path); }

    @Test void exportsDifferentDraftPublishedMarkdownOwnImagesAndVerifiableManifestWithoutMutating() throws Exception {
        var draft = contents.create("ARTICLE", null);
        var image = assets.upload(draft.id(), png());
        createdFiles.add(Path.of("target/export-assets", image.id().replace("-", "")));
        var other = contents.create("ARTICLE", null);
        var otherImage = assets.upload(other.id(), png());
        createdFiles.add(Path.of("target/export-assets", otherImage.id().replace("-", "")));
        var saved = contents.save(draft.id(), new ArticleInput(draft.version(), draft.slug(), "公开", "摘要",
            "# 公开\n\n![图片](" + image.url() + ")", List.of("中文", "Case"), image.url(), "未分类", true));
        var published = contents.publish(saved.id(), new VersionInput(saved.version(), null, true));
        var edited = contents.save(published.id(), new ArticleInput(published.version(), published.slug(), "私密", "工作摘要",
            "# 私密\n\n![旧图](" + image.url() + ")", List.of("private"), image.url(), "未分类", true));
        var response = mvc.perform(get(endpoint(edited.id(), edited.version())).with(user("owner")))
            .andExpect(status().isOk()).andExpect(content().contentType("application/zip"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().exists("X-Request-Id"))
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"raychi-" + edited.id() + "-v" + edited.version() + ".zip\""))
            .andReturn().getResponse();
        var files = unpack(response.getContentAsByteArray());
        String imagePath = "assets/" + image.id() + ".png";
        assertThat(files.keySet()).containsExactly("draft.md", "published.md", imagePath, "content.json", "manifest.json");
        assertThat(new String(files.get("draft.md"), StandardCharsets.UTF_8)).contains("私密", imagePath).doesNotContain(image.url());
        assertThat(new String(files.get("published.md"), StandardCharsets.UTF_8)).contains("公开", imagePath);
        assertThat(ImageIO.read(new ByteArrayInputStream(files.get(imagePath))).getWidth()).isEqualTo(2);
        var metadata = json.readTree(files.get("content.json"));
        assertThat(metadata.path("draft").path("bodyMarkdown").asText()).isEqualTo(edited.bodyMarkdown());
        assertThat(metadata.path("published").path("bodyMarkdown").asText()).contains("# 公开");
        assertThat(metadata.path("attachments").size()).isEqualTo(1);
        assertThat(metadata.path("attachments").get(0).path("sha256").asText()).isEqualTo(sha(files.get(imagePath)));
        for (var entry : json.readTree(files.get("manifest.json")).path("files")) {
            byte[] bytes = files.get(entry.path("path").asText());
            assertThat(entry.path("byteSize").asLong()).isEqualTo(bytes.length);
            assertThat(entry.path("sha256").asText()).isEqualTo(sha(bytes));
        }
        assertThat(response.getContentAsByteArray().length).isEqualTo(response.getContentLength());
        assertThat(contents.getAdmin(edited.id()).version()).isEqualTo(edited.version());
        assertThat(contents.getPublic("ARTICLE", edited.slug()).title()).isEqualTo("公开");
        var withdrawn = contents.unpublish(edited.id(), new VersionInput(edited.version()));
        assertThat(unpack(exports.export(withdrawn.id(), withdrawn.version()).bytes())).containsKey("published.md");
    }

    @Test void privatePostDraftHasNoPublishedFileAndRequiresAuthVersionAndValidTarget() throws Exception {
        var draft = contents.create("POST", null);
        var saved = contents.save(draft.id(), new ArticleInput(draft.version(), draft.slug(), "帖子", "", "私密帖子正文", List.of(), null, null));
        var files = unpack(exports.export(saved.id(), saved.version()).bytes());
        assertThat(files.keySet()).containsExactly("draft.md", "content.json", "manifest.json");
        assertThat(json.readTree(files.get("content.json")).path("published").isNull()).isTrue();
        mvc.perform(get(endpoint(saved.id(), saved.version()))).andExpect(status().isUnauthorized());
        mvc.perform(get(endpoint(saved.id(), saved.version() - 1)).with(user("owner")))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(get("/api/v1/admin/contents/" + saved.id() + "/export").with(user("owner"))).andExpect(status().isBadRequest());
        mvc.perform(get(endpoint(saved.id(), -1)).with(user("owner"))).andExpect(status().isBadRequest());
        mvc.perform(get(endpoint("missing", 1)).with(user("owner"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/public/contents/POST/" + saved.slug() + "/export")).andExpect(status().isNotFound());
    }

    @Test void oversizedOrMissingImagesFailBeforeReturningAnyPartialArchive() throws Exception {
        var draft = contents.create("ARTICLE", null);
        var image = assets.upload(draft.id(), png());
        var file = Path.of("target/export-assets", image.id().replace("-", ""));
        createdFiles.add(file);
        db.update("UPDATE assets SET byte_size=? WHERE id=?", 64L * 1024 * 1024 + 1, image.id());
        mvc.perform(get(endpoint(draft.id(), draft.version())).with(user("owner")))
            .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("CONTENT_EXPORT_TOO_LARGE"));
        db.update("UPDATE assets SET byte_size=? WHERE id=?", Files.size(file), image.id());
        Files.write(file, new byte[]{1}, java.nio.file.StandardOpenOption.APPEND);
        mvc.perform(get(endpoint(draft.id(), draft.version())).with(user("owner")))
            .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("CONTENT_EXPORT_FAILED"));
        Files.delete(file);
        mvc.perform(get(endpoint(draft.id(), draft.version())).with(user("owner")))
            .andExpect(status().isInternalServerError()).andExpect(content().contentTypeCompatibleWith("application/json"))
            .andExpect(jsonPath("$.code").value("CONTENT_EXPORT_FAILED"))
            .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test void includes256RealAttachmentsAndRejectsThe257thBeforeReading() throws Exception {
        var draft = contents.create("ARTICLE", null);
        for (int i = 0; i < 256; i++) {
            var image = assets.upload(draft.id(), png());
            createdFiles.add(Path.of("target/export-assets", image.id().replace("-", "")));
        }
        var files = unpack(exports.export(draft.id(), draft.version()).bytes());
        assertThat(json.readTree(files.get("content.json")).path("attachments").size()).isEqualTo(256);
        var extra = assets.upload(draft.id(), png());
        createdFiles.add(Path.of("target/export-assets", extra.id().replace("-", "")));
        mvc.perform(get(endpoint(draft.id(), draft.version())).with(user("owner")))
            .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("CONTENT_EXPORT_TOO_LARGE"));
    }

    private static String endpoint(String id, long version) { return "/api/v1/admin/contents/" + id + "/export?expectedVersion=" + version; }
    private static MockMultipartFile png() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
        return new MockMultipartFile("file", "fixture.png", "image/png", out.toByteArray());
    }
    private static Map<String, byte[]> unpack(byte[] bytes) throws Exception {
        var result = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry())
                assertThat(result.put(entry.getName(), zip.readAllBytes())).isNull();
        }
        return result;
    }
    private static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
