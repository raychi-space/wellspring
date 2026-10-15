package space.raychi.wellspring;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reading;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "raychi.reading.initial-delay-ms=99999999", "raychi.reading.translation-poll-ms=99999999"
})
@AutoConfigureMockMvc
@Transactional
class ReadingFlowTest {
    @DynamicPropertySource
    static void credentials(DynamicPropertyRegistry registry) {
        registry.add("raychi.admin.username", () -> "test-admin");
        registry.add("raychi.admin.password-hash", () -> new BCryptPasswordEncoder().encode("test-password"));
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;

    @Test
    void publicShelfShowsEnabledSourcesAndFallsBackWhenTranslationIsPending() throws Exception {
        Instant now = Instant.now();
        db.update("INSERT INTO reading_articles(id,source_id,external_url,external_url_hash,original_title,translated_title,published_at,discovered_at,updated_at,title_hash,translation_status,translation_task_id,translation_attempts,next_translation_at,translation_revision) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), "anthropic-engineering", "https://www.anthropic.com/engineering/example",
                "b".repeat(64), "Original title", null, Timestamp.from(now.minusSeconds(3600)), Timestamp.from(now), Timestamp.from(now),
                "a".repeat(64), "PENDING", null, 0, Timestamp.from(now), 0);
        mvc.perform(get("/api/v1/public/reading"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayDays").value(90))
                .andExpect(jsonPath("$.sources[0].id").value("openai-developers"))
                .andExpect(jsonPath("$.sources[1].articles[0].originalTitle").value("Original title"))
                .andExpect(jsonPath("$.sources[1].articles[0].translatedTitle").doesNotExist())
                .andExpect(jsonPath("$.sources[1].articles[0].url").value("https://www.anthropic.com/engineering/example"));
    }

    @Test
    @WithMockUser
    void adminCanBindTranslationAssistantAndPauseSource() throws Exception {
        mvc.perform(put("/api/v1/admin/reading/settings").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":0,\"translationAssistantId\":\"translator-1\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.translationAssistantId").value("translator-1"));
        mvc.perform(put("/api/v1/admin/reading/sources/openai-developers").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"OpenAI Developer Blog","description":"开发者文章",
                                "siteUrl":"https://developers.openai.com/blog",
                                "listingUrl":"https://developers.openai.com/blog",
                                "adapter":"OPENAI_DEVELOPERS","enabled":false,"position":10}
                                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        mvc.perform(get("/api/v1/public/reading"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sources.length()").value(1));
    }

    @Test
    void adminRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/admin/reading")).andExpect(status().isUnauthorized());
    }
}
