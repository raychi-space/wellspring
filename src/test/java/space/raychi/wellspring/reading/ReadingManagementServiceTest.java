package space.raychi.wellspring.reading;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import space.raychi.wellspring.api.ApiException;

class ReadingManagementServiceTest {
    private HttpServer upstream;
    private ExecutorService executor;
    private final ObjectMapper json=new ObjectMapper();
    private final AtomicInteger hits=new AtomicInteger(),status=new AtomicInteger(200);
    private final AtomicReference<String> auth=new AtomicReference<>(),cookie=new AtomicReference<>(),path=new AtomicReference<>(),method=new AtomicReference<>(),requestBody=new AtomicReference<>();
    private volatile String response;
    private ReadingManagementService service;
    private AiHotClient client;
    private static final String TOKEN="a".repeat(43);

    @BeforeEach void setup() throws Exception {
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newCachedThreadPool();upstream.setExecutor(executor);
        upstream.createContext("/",exchange->{
            hits.incrementAndGet();auth.set(exchange.getRequestHeaders().getFirst("Authorization"));cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
            path.set(exchange.getRequestURI().toString());method.set(exchange.getRequestMethod());requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.getResponseHeaders().set("Location","/should-not-follow");
            exchange.sendResponseHeaders(status.get(),bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}catch(java.io.IOException ignored){}
        });upstream.start();
        client=new AiHotClient(json,"http://127.0.0.1:"+upstream.getAddress().getPort(),true,TOKEN);
        service=new ReadingManagementService(client,json);
        response=source();
    }
    @AfterEach void stop(){upstream.stop(0);executor.shutdownNow();}
    private static String source(){return """
        {"schemaVersion":1,"id":"rss-test","name":"Test","enabled":true,"health":"ok","intervalMinutes":30,
         "lastSuccessAt":null,"lastFetchAt":null,"nextFetchAt":null,"lastError":null,"feedUrl":"https://example.org/rss","version":"2026-11-11T00:00:00.000Z",
         "token":"SECRET_SENTINEL","config":{"apiKey":"SECRET_SENTINEL"}}
        """;}
    @Test void fixedTransportAndAllowlistedDto() throws Exception {
        var result=service.source("rss-test");
        assertEquals("Test",result.name());assertEquals("Bearer "+TOKEN,auth.get());assertNull(cookie.get());
        assertEquals("/api/internal/raychi-management/v1/sources/rss-test",path.get());assertEquals("GET",method.get());
        assertFalse(json.writeValueAsString(result).contains("SECRET_SENTINEL"));
    }
    @Test void validationPrecedesNetworkAndCannotProxyArbitraryPaths() {
        for(var q:java.util.List.of(Map.of("url","http://private"),Map.of("page","0"),Map.of("kind","other")))
            assertEquals(400,assertThrows(ApiException.class,()->service.dashboard(q)).status().value());
        assertThrows(ApiException.class,()->service.source("../other"));
        assertThrows(IllegalArgumentException.class,()->client.manage("DELETE","/sources/rss-test",null));
        assertThrows(IllegalArgumentException.class,()->client.manage("GET","/../auth",null));
        assertEquals(0,hits.get());
    }
    @Test void queryValuesAreEncodedAndUnknownUpstreamFieldsDropped() {
        response="{\"schemaVersion\":1,\"page\":1,\"pageSize\":100,\"total\":0,\"items\":[],\"credentials\":\"SECRET_SENTINEL\"}";
        assertEquals(0,service.sources(Map.of("q","a&url=http://private")).total());
        assertTrue(path.get().contains("q=a%26url%3Dhttp%3A%2F%2Fprivate"));
    }
    @Test void internalAuthAndServerFailuresNeverExpireBrowserSessionOrReplayWrites() {
        for(int code:new int[]{401,403,500,503,308}) {
            status.set(code);response="{\"code\":\"UNAUTHORIZED\",\"message\":\"SECRET_SENTINEL\"}";
            int before=hits.get();ApiException error=assertThrows(ApiException.class,()->service.collect("rss-test"));
            assertEquals(503,error.status().value());assertEquals("READING_MANAGEMENT_UNAVAILABLE",error.code());
            assertFalse(error.getMessage().contains("SECRET"));assertEquals(before+1,hits.get());
        }
    }
    @Test void noConfigurationMakesNoNetworkRequest() {
        var empty=new AiHotClient(json,"http://127.0.0.1:"+upstream.getAddress().getPort(),true);
        assertFalse(empty.managementConfigured());
        var error=assertThrows(ApiException.class,()->new ReadingManagementService(empty,json).models());
        assertEquals("READING_MANAGEMENT_UNCONFIGURED",error.code());assertEquals(0,hits.get());
    }
    @Test void schemaMissingFieldsAndOversizeResponsesAreUnavailable() {
        for(String body:new String[]{"{}","{\"schemaVersion\":2}","{\"schemaVersion\":1}","[1,2]","x".repeat(1_000_001)}) {
            response=body;assertEquals(503,assertThrows(ApiException.class,()->service.source("rss-test")).status().value());
        }
    }
    @Test void nativeConflictsBecomeSafeSiteErrors() {
        status.set(409);response="{\"code\":\"SOURCE_DUPLICATE\",\"password\":\"SECRET_SENTINEL\"}";
        var error=assertThrows(ApiException.class,()->service.source("rss-test"));assertEquals(409,error.status().value());
        assertEquals("该RSS来源已存在。",error.getMessage());
    }
    @Test void modelResetRequiresExplicitFieldAndPresetNamesMayContainDots() throws Exception {
        assertThrows(ApiException.class,()->service.switchModel("score",json.readTree("{}")));
        assertThrows(ApiException.class,()->service.switchModel("score",json.readTree("{\"model\":\"a\",\"token\":\"x\"}")));
        assertEquals(0,hits.get());
        response="{\"schemaVersion\":1,\"checkedAt\":\"2026-11-11T00:00:00Z\",\"metadataAvailable\":true,\"metadataAt\":null,\"days\":7,\"choices\":[],\"capabilities\":[],\"budgets\":[],\"summary\":{\"calls\":0,\"ok\":0,\"failed\":0,\"unknown\":0,\"p50Ms\":null,\"p95Ms\":null,\"tokensIn\":0,\"tokensOut\":0}}";
        service.switchModel("score",json.readTree("{\"model\":\"glm-5.3-flash\"}"));
        assertEquals("PUT",method.get());assertEquals("glm-5.3-flash",json.readTree(requestBody.get()).path("model").asText());
    }
    @Test void budgetsAndIntervalsCannotBeSilentlyRoundedOrCoerced() {
        for(String value:new String[]{"1.5","\"10\"","true","99999999999999999999999"}) {
            assertThrows(Exception.class,()->json.readValue("{\"perMinute\":"+value+",\"perHour\":60,\"perDay\":300}",ReadingManagementModels.BudgetInput.class));
            assertThrows(Exception.class,()->json.readValue("{\"name\":\"a\",\"feedUrl\":\"https://example.org\",\"intervalMinutes\":"+value+"}",ReadingManagementModels.SourceCreate.class));
        }
    }
}
