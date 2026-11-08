package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:analytics-details;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","raychi.admin.username=details-admin","raychi.analytics.enabled=true","raychi.analytics.token=private-fixture-token","raychi.analytics.origin=https://fixture.example"})
@AutoConfigureMockMvc
class AnalyticsDetailsTest {
    static final CopyOnWriteArrayList<String> payloads=new CopyOnWriteArrayList<>();
    static final HttpServer core;
    static {
        try {
            core=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            core.createContext("/v2/apps/raychi-public/",exchange->{
                if(exchange.getRequestMethod().equals("POST")) {
                    payloads.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                    exchange.sendResponseHeaders(202,-1);
                } else {
                    String metadata="\"appId\":\"raychi-public\",\"from\":\"2026-11-07\",\"to\":\"2026-11-08\",\"retention\":{\"rawDays\":30,\"aggregateDays\":365,\"timeZone\":\"Asia/Shanghai\",\"geoConfigured\":true,\"visitorTrackingSince\":\"2026-11-07T00:00:00Z\",\"secret\":\"hidden\"}";
                    String body=exchange.getRequestURI().getPath().endsWith("/events")
                      ? "{"+metadata+",\"total\":1,\"page\":1,\"pageSize\":20,\"items\":[{\"id\":\"one\",\"occurredAt\":\"2026-11-07T00:00:00Z\",\"ip\":\"114.114.114.114\",\"path\":\"/reading\",\"source\":\"direct_or_unknown\",\"country\":\"中国\",\"province\":\"江苏省\",\"city\":\"南京市\",\"visitor\":\"hidden\"}]}"
                      : "{"+metadata+",\"summary\":{\"pageViews\":1,\"visitors\":1,\"todayPageViews\":1,\"todayVisitors\":1},\"trend\":[],\"countries\":[],\"provinces\":[],\"cities\":[],\"sources\":[],\"popularPaths\":[],\"token\":\"hidden\"}";
                    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
                }
                exchange.close();
            });core.start();
        }catch(Exception ex){throw new ExceptionInInitializerError(ex);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("raychi.analytics.url",()->"http://127.0.0.1:"+core.getAddress().getPort());
        registry.add("raychi.admin.password-hash",()->new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("fixture-password"));
    }
    @AfterAll static void stop(){core.stop(0);}
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Test void capturesEveryPublicPathWithServerIpAndNeverTrustsBrowserIp() throws Exception {
        for(String path:new String[]{"/reading","/search","/not-found","/writing/unpublished"}) {
            String id=UUID.randomUUID().toString();
            String body=json.createObjectNode().put("id",id).put("path",path).put("occurredAt",Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString()).toString();
            mvc.perform(post("/api/v1/public/analytics/events").with(csrf()).with(request->{request.setRemoteAddr("114.114.114.114");return request;}).header("Origin","https://fixture.example").header("User-Agent","Mozilla/5.0").header("X-Forwarded-For","203.0.113.9").contentType("application/json").content(body)).andExpect(status().isAccepted());
            long end=System.currentTimeMillis()+3000;
            while(payloads.stream().noneMatch(value->value.contains(id))&&System.currentTimeMillis()<end)Thread.sleep(10);
            String delivered=payloads.stream().filter(value->value.contains(id)).findFirst().orElseThrow();
            assertThat(json.readTree(delivered).path("ip").asText()).isEqualTo("114.114.114.114");
            assertThat(delivered).doesNotContain("203.0.113.9");
        }
    }
    @Test void rawDetailsRequireAdminAndOnlyContractualFieldsLeaveTheBridge() throws Exception {
        String query="?from=2026-11-07&to=2026-11-08";
        for(String endpoint:new String[]{"overview","events"}) {
            String path="/api/v1/admin/analytics/"+endpoint+query;
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).with(user("reader"))).andExpect(status().isForbidden());
            mvc.perform(get(path).with(user("owner").roles("ADMIN"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
              .andExpect(jsonPath("$.token").doesNotExist()).andExpect(jsonPath("$.retention.secret").doesNotExist()).andExpect(jsonPath("$.items[0].visitor").doesNotExist());
        }
        for(String bad:new String[]{"&ip=hostname.example","&page=0","&pageSize=101","&ip=abc","&path=/studio","&from=2026-11-06","&secret=x"})
            mvc.perform(get("/api/v1/admin/analytics/events"+query+bad).with(user("owner").roles("ADMIN"))).andExpect(status().isBadRequest());
    }
}
