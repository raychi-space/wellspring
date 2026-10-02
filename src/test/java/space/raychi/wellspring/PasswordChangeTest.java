package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import space.raychi.wellspring.service.AdminAccountService;
import space.raychi.wellspring.mapper.AdminAccountMapper;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:password_change;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "raychi.admin.username=password-admin"
})
@AutoConfigureMockMvc
class PasswordChangeTest {
    private static final String OLD = "initial-password", NEXT = "replacement-password";
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("raychi.admin.password-hash", () -> new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(OLD));
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder encoder;
    @Autowired AdminAccountService accounts;
    @Autowired AdminAccountMapper mapper;

    @Test
    void changesPersistAndRevokeAllPreviouslyAuthenticatedSessions() throws Exception {
        mvc.perform(post("/api/v1/auth/password").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(OLD, NEXT))).andExpect(status().isUnauthorized());
        var first = login(OLD);
        var other = login(OLD);
        var sessionCheck = login(OLD);
        mvc.perform(post("/api/v1/auth/password").session(first).contentType(MediaType.APPLICATION_JSON)
                .content(body(OLD, NEXT))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CSRF_INVALID"));
        change(first, "wrong-password", NEXT, "CURRENT_PASSWORD_INVALID");
        change(first, OLD, "short", "VALIDATION_FAILED");
        change(first, OLD, OLD, "VALIDATION_FAILED");
        change(first, OLD, "密".repeat(25), "VALIDATION_FAILED");
        change(first, OLD, " ".repeat(12), "VALIDATION_FAILED");
        change(first, null, NEXT, "VALIDATION_FAILED");
        mvc.perform(get("/api/v1/auth/session").session(first)).andExpect(jsonPath("$.authenticated").value(true));
        long before = mapper.select().credentialVersion();
        mvc.perform(post("/api/v1/auth/password").session(first).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body(OLD, NEXT)))
                .andExpect(status().isNoContent()).andExpect(content().string(""));
        assertThat(first.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/admin/contents").session(other)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/auth/session").session(sessionCheck)).andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false));
        assertThat(mapper.select().credentialVersion()).isEqualTo(before + 1);
        String hash = mapper.select().passwordHash();
        assertThat(hash).isNotEqualTo(NEXT);
        assertThat(encoder.matches(NEXT, hash)).isTrue();
        accounts.initialize("another-bootstrap-user", encoder.encode(OLD));
        assertThat(mapper.select().passwordHash()).isEqualTo(hash);
        assertThat(mapper.select().username()).isEqualTo("password-admin");
        assertThat(mapper.changePassword("password-admin", before, encoder.encode(OLD))).isZero();
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "password-admin", "password", OLD))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        var fresh = login(NEXT);
        mvc.perform(get("/api/v1/admin/contents").session(fresh)).andExpect(status().isOk());
    }

    private MockHttpSession login(String password) throws Exception {
        var result = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", "password-admin", "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(true)).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
    private String body(String current, String next) throws Exception {
        return json.writeValueAsString(new space.raychi.wellspring.dto.ChangePasswordRequest(current, next));
    }
    private void change(MockHttpSession session, String current, String next, String code) throws Exception {
        mvc.perform(post("/api/v1/auth/password").session(session).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(body(current, next))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(code));
    }
}
