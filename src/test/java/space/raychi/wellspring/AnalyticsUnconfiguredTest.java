package space.raychi.wellspring;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:analytics_unconfigured;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "raychi.analytics.enabled=false", "raychi.admin.username=fixture",
        "raychi.admin.password-hash=$2a$12$Z82UUshzHfz1uyfb7wLV3e61/d1YSDncKx7M7kxlHRCFFNzMN1cUu"
})
@AutoConfigureMockMvc
class AnalyticsUnconfiguredTest {
    @Autowired MockMvc mvc;

    @Test void unconfiguredDeploymentCannotBeEnabledFromAdmin() throws Exception {
        String path = "/api/v1/admin/analytics/settings";
        mvc.perform(get(path).with(user("owner").roles("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.version").value(0));
        mvc.perform(put(path).with(user("owner").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"version\":0}"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ANALYTICS_NOT_CONFIGURED"));
        mvc.perform(put(path).with(user("owner").roles("ADMIN")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.version").value(1));
        mvc.perform(get("/api/v1/admin/analytics/report?from=2026-10-01&to=2026-10-09").with(user("owner")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("ANALYTICS_DISABLED"));
    }
}
