package space.raychi.wellspring;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import space.raychi.wellspring.reading.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:discovery;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "raychi.reading.aihot.enabled=true",
    "raychi.reading.aihot.url=http://127.0.0.1:1"})
@AutoConfigureMockMvc
class DiscoveryFlowTest {
    @org.springframework.test.context.DynamicPropertySource
    static void credentials(org.springframework.test.context.DynamicPropertyRegistry registry) {
        registry.add("raychi.admin.username", () -> "discovery-test-admin");
        registry.add("raychi.admin.password-hash", () -> new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("isolated-test-password"));
    }
    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired JdbcTemplate db;

    @Test void publicContractIsAvailableWhileUpstreamIsDownAndOldDataRemainsReadable() throws Exception {
        mvc.perform(get("/api/v1/public/reading/discovery")).andExpect(status().isOk())
            .andExpect(jsonPath("$.mode").value("aihot")).andExpect(jsonPath("$.available").value(false));
        mvc.perform(get("/api/v1/public/reading")).andExpect(status().isOk()).andExpect(jsonPath("$.sources.length()").value(2));
        mvc.perform(get("/api/v1/admin/reading/integration")).andExpect(status().isUnauthorized());
        assertTrue(context.getBeansOfType(ReadingSyncWorker.class).isEmpty());
        assertTrue(context.getBeansOfType(ReadingTranslationWorker.class).isEmpty());
    }

    @Test @WithMockUser void managementIsReadOnlyAndOldScheduledWorkCannotBeTriggered() throws Exception {
        int sources = db.queryForObject("SELECT count(*) FROM reading_sources", Integer.class);
        mvc.perform(get("/api/v1/admin/reading/integration")).andExpect(status().isOk())
            .andExpect(jsonPath("$.legacyActive").value(false)).andExpect(jsonPath("$.connected").value(false));
        mvc.perform(post("/api/v1/admin/reading/sources/openai-developers/sync")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/reading/sources/openai-developers/sync").with(csrf()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("READING_MANAGED_EXTERNALLY"));
        mvc.perform(delete("/api/v1/admin/reading/sources/openai-developers").with(csrf())).andExpect(status().isConflict());
        assertEquals(sources, db.queryForObject("SELECT count(*) FROM reading_sources", Integer.class));
    }
}
