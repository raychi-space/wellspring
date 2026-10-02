package space.raychi.wellspring.ai;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import space.raychi.wellspring.config.SecurityConfig;

@WebMvcTest(AgentController.class)
@Import(SecurityConfig.class)
@org.springframework.test.context.TestPropertySource(
    properties = {
      "raychi.admin.username=fixture-admin",
      "raychi.admin.password-hash=$2a$10$fixtureFixtureFixtureFixtureFixtureFixtureFixtureFixture"
    })
class AgentAuthorizationTest {
  @Autowired MockMvc mvc;
  @MockitoBean space.raychi.wellspring.service.AdminAccountService accounts;
  @MockitoBean AgentClient client;
  @MockitoBean WritingService writing;

  @Test
  void anonymousAndNonAdminCannotReadConfiguration() throws Exception {
    mvc.perform(get("/api/v1/admin/ai/providers")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/admin/ai/providers").with(user("viewer").roles("VIEWER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void mutationsRequireAdminAndCsrf() throws Exception {
    mvc.perform(
            post("/api/v1/admin/ai/providers")
                .with(user("owner").roles("ADMIN"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/admin/ai/providers")
                .with(user("viewer").roles("VIEWER"))
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/admin/ai/providers")
                .with(user("owner").roles("ADMIN"))
                .with(csrf())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isCreated());
  }
}
