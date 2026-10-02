package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import space.raychi.wellspring.service.ArticleService;
import space.raychi.wellspring.service.AssetService;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:api_contract;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "raychi.admin.username=test-admin", "raychi.assets.dir=./target/contract-assets"
})
@AutoConfigureMockMvc
class ApiContractTest {
    @DynamicPropertySource
    static void password(DynamicPropertyRegistry registry) {
        registry.add("raychi.admin.password-hash", () -> new BCryptPasswordEncoder().encode("test-password"));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired ArticleService articles;
    @Autowired AssetService assets;
    @Autowired JdbcTemplate db;

    @Test
    @WithMockUser
    void jsonResponsesExposeDtosAndKeepCreationLocation() throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/admin/articles").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andExpect(header().exists("Location"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.bodyMarkdown").value(""))
                .andExpect(jsonPath("$.draftBody").doesNotExist())
                .andExpect(jsonPath("$.publicBody").doesNotExist()).andReturn();
        String id = json.readTree(result.getResponse().getContentAsString()).get("id").asText();
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/admin/articles/" + id);
        mvc.perform(get("/api/v1/public/settings")).andExpect(status().isOk())
                .andExpect(jsonPath("$.siteName").isString()).andExpect(jsonPath("$.valueJson").doesNotExist());
    }

    @Test
    @WithMockUser
    void malformedRequestsAndBusinessFailuresUseOneErrorContract() throws Exception {
        assertError(mvc.perform(post("/api/v1/admin/contents?type=ARTICLE").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andReturn(), "VALIDATION_FAILED");
        assertError(mvc.perform(get("/api/v1/public/contents?page=wrong"))
                .andExpect(status().isBadRequest()).andReturn(), "VALIDATION_FAILED");
        assertError(mvc.perform(post("/api/v1/admin/contents").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andReturn(), "VALIDATION_FAILED");
        assertError(mvc.perform(get("/api/v1/admin/articles/missing"))
                .andExpect(status().isNotFound()).andReturn(), "ARTICLE_NOT_FOUND");
        assertError(mvc.perform(delete("/api/v1/public/settings").with(csrf()))
                .andExpect(status().isMethodNotAllowed()).andExpect(header().exists("Allow")).andReturn(), "METHOD_NOT_ALLOWED");
        assertError(mvc.perform(post("/api/v1/admin/contents?type=ARTICLE").with(csrf())
                .contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType()).andReturn(), "MEDIA_TYPE_NOT_SUPPORTED");
        assertError(mvc.perform(get("/api/v1/public/settings").accept(MediaType.TEXT_PLAIN))
                .andExpect(status().isNotAcceptable()).andReturn(), "MEDIA_TYPE_NOT_ACCEPTABLE");
        assertError(mvc.perform(get("/api/v1/public/unknown"))
                .andExpect(status().isNotFound()).andReturn(), "RESOURCE_NOT_FOUND");
    }

    @Test
    void securityFailuresHaveTheSameRequestIdAndErrorFields() throws Exception {
        assertError(mvc.perform(get("/api/v1/admin/settings"))
                .andExpect(status().isUnauthorized()).andReturn(), "AUTH_REQUIRED");
        assertError(mvc.perform(post("/api/v1/admin/contents?type=ARTICLE")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andReturn(), "CSRF_INVALID");
    }

    @Test
    @Transactional
    void unexpectedFailuresReturnSanitizedErrors() throws Exception {
        db.update("UPDATE site_settings SET value_json=? WHERE id=1", "broken stored JSON");
        MvcResult result = mvc.perform(get("/api/v1/public/settings"))
                .andExpect(status().isInternalServerError()).andReturn();
        assertError(result, "INTERNAL_ERROR");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("broken stored JSON", "Invalid site settings", "stackTrace");
    }

    @Test
    @WithMockUser
    void imageGetAndHeadKeepBinaryHeadersAndDraftAuthorization() throws Exception {
        var draft = articles.create(null);
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", output);
        byte[] bytes = output.toByteArray();
        var image = assets.upload(draft.id(), new MockMultipartFile("file", "x.png", "image/png", bytes));
        mvc.perform(get(image.previewUrl())).andExpect(status().isOk())
                .andExpect(content().bytes(bytes)).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(head(image.previewUrl())).andExpect(status().isOk()).andExpect(content().string(""))
                .andExpect(header().string("Content-Length", Integer.toString(bytes.length)))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        assertError(mvc.perform(get(image.url())).andExpect(status().isNotFound()).andReturn(), "ASSET_NOT_FOUND");
    }

    @Test
    void loginRotatesSessionAndLogoutReturnsNoContent() throws Exception {
        MvcResult tokenResult = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        var token = json.readTree(tokenResult.getResponse().getContentAsString());
        MockHttpSession session = (MockHttpSession) tokenResult.getRequest().getSession(false);
        String oldId = session.getId();
        mvc.perform(post("/api/v1/auth/login").session(session)
                .header(token.get("headerName").asText(), token.get("token").asText())
                .contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"test-admin\",\"password\":\"test-password\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true));
        assertThat(session.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/v1/auth/session").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test-admin"));
        MvcResult nextToken = mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn();
        token = json.readTree(nextToken.getResponse().getContentAsString());
        mvc.perform(post("/api/v1/auth/logout").session(session)
                .header(token.get("headerName").asText(), token.get("token").asText()))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        mvc.perform(get("/api/v1/auth/session")).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    private void assertError(MvcResult result, String code) throws Exception {
        var body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("message").asText()).isNotBlank();
        assertThat(body.get("requestId").asText()).isEqualTo(result.getResponse().getHeader("X-Request-Id")).isNotBlank();
        assertThat(body.get("fieldErrors").isArray()).isTrue();
        assertThat(body.has("data")).isFalse();
    }
}
