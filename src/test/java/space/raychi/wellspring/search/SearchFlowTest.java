package space.raychi.wellspring.search;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import space.raychi.wellspring.article.ArticleService;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:search;MODE=MySQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","raychi.admin.username=test","raychi.admin.password-hash=$2a$10$trITbMJ1TRJPxkuerFtT3O.TE.JJCbAbIDZM/RB.QsbePc/FmuQui","raychi.search.enabled=true","raychi.search.token=fixture-search-token-0123456789","raychi.search.namespace=demo","raychi.search.poll-ms=3600000"})
@AutoConfigureMockMvc
class SearchFlowTest {
    static final ObjectMapper JSON=new ObjectMapper();
    static HttpServer fake;
    static volatile int httpStatus=200;
    static volatile List<Map<String,Object>> hits=List.of();
    static volatile CountDownLatch started,release;
    static volatile Map<String,Object> lastBody;
    static {
        try {
            fake=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            fake.createContext("/",exchange->{
                try {
                    String path=exchange.getRequestURI().getPath();
                    String input=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    var body=input.isBlank()?Map.<String,Object>of():JSON.readValue(input,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
                    lastBody=body;
                    if(started!=null && !path.endsWith("/search")){started.countDown();release.await(5,TimeUnit.SECONDS);}
                    Object response;
                    if(path.endsWith("/search"))response=Map.of("hits",hits,"nextOffset",20);
                    else if(path.endsWith("/index/replace"))response=Map.of("replaced",((List<?>)body.get("entries")).size());
                    else response=Map.of("outcome","applied","version",exchange.getRequestMethod().equals("DELETE")?Long.parseLong(exchange.getRequestURI().getQuery().split("=")[1]):body.get("version"));
                    byte[] data=JSON.writeValueAsBytes(response);exchange.sendResponseHeaders(httpStatus,data.length);exchange.getResponseBody().write(data);
                }catch(Exception ignored){} finally{exchange.close();}
            });fake.setExecutor(Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;}));fake.start();
        }catch(Exception ex){throw new ExceptionInInitializerError(ex);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("raychi.search.url",()->"http://127.0.0.1:"+fake.getAddress().getPort());}
    @Autowired ArticleService content;
    @Autowired SearchState state;
    @Autowired SearchWorker worker;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @BeforeEach void reset(){httpStatus=200;hits=List.of();started=null;release=null;db.update("DELETE FROM search_sync_state");db.update("DELETE FROM articles");}
    @AfterAll static void stop(){fake.stop(0);}
    private ArticleService.AdminArticle published() {
        var draft=content.create("ARTICLE",new ArticleService.ArticleInput(null,"search-"+UUID.randomUUID(),"数据库迁移","","公开 **正文** [说明](https://example.com/private-url)",List.of(),null));
        return content.publish(draft.id(),new ArticleService.VersionInput(draft.version()));
    }
    private Map<String,Object> hit(String id,long version){return Map.of("id","content:"+id,"version",version,"type","article","title","旧标题","snippet","公开正文","score",1);}
    @Test void generatedPostTitleComesFromAuthoritativeSnapshot() throws Exception {
        var draft=content.create("POST",new ArticleService.ArticleInput(null,null,"","","数据库随笔，公开正文。",List.of(),null));
        var post=content.publish(draft.id(),new ArticleService.VersionInput(draft.version()));
        hits=List.of(Map.of("id","content:"+post.id(),"version",1,"type","post","title","不可信的旧标题","snippet","数据库随笔","score",1));
        mvc.perform(get("/api/v1/public/search").param("q","数据库")).andExpect(status().isOk()).andExpect(jsonPath("$.hits[0].title").value("数据库随笔，公开正文。"));
    }
    @Test void contentAndDesiredStateRollbackTogether() {
        var draft=content.create("ARTICLE",new ArticleService.ArticleInput(null,"rollback-"+UUID.randomUUID(),"事务回滚","","公开内容",List.of(),null));
        db.execute("ALTER TABLE search_sync_state ADD CONSTRAINT fixture_reject CHECK (version < 1)");
        try {
            assertThatThrownBy(()->content.publish(draft.id(),new ArticleService.VersionInput(draft.version()))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(content.getAdmin(draft.id()).status()).isEqualTo("DRAFT");
            assertThat(content.getAdmin(draft.id()).version()).isZero();
            assertThat(state.snapshot()).isEmpty();
        }finally {db.execute("ALTER TABLE search_sync_state DROP CONSTRAINT fixture_reject");}
    }
    @Test void publicProjectionAndDraftIsolationDeletionTombstone() throws Exception {
        var p=published();var before=state.snapshot().getFirst();assertThat(before.version()).isEqualTo(1);assertThat(before.document().get("body")).isEqualTo("公开 正文 说明");
        var draft=content.save(p.id(),new ArticleService.ArticleInput(p.version(),p.slug(),"私人标题","","私人内容",List.of(),null));
        assertThat(state.snapshot().getFirst().version()).isEqualTo(1);
        hits=List.of(hit(p.id(),1));mvc.perform(get("/api/v1/public/search").param("q","数据库")).andExpect(status().isOk()).andExpect(jsonPath("$.hits[0].title").value("数据库迁移"));
        content.unpublish(p.id(),new ArticleService.VersionInput(draft.version()));
        mvc.perform(get("/api/v1/public/search").param("q","数据库")).andExpect(status().isOk()).andExpect(jsonPath("$.hits").isEmpty()).andExpect(jsonPath("$.nextOffset").value(20));
        var hidden=content.getAdmin(p.id());content.delete(p.id(),new ArticleService.VersionInput(hidden.version()));
        assertThat(state.snapshot().getFirst().action()).isEqualTo("DELETE");assertThat(state.snapshot().getFirst().document()).isNull();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM articles",Long.class)).isZero();
    }
    @Test void staleSnippetsAndInputAndServiceErrors() throws Exception {
        var p=published();hits=List.of(hit(p.id(),99));mvc.perform(get("/api/v1/public/search").param("q","数据库")).andExpect(jsonPath("$.hits").isEmpty());
        for(String q:List.of("","数","a"))mvc.perform(get("/api/v1/public/search").param("q",q)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/public/search").param("q","数据库").param("type","ARTICLE")).andExpect(status().isBadRequest());
        assertThatThrownBy(()->new SearchClient(JSON,"http://127.0.0.1:8091","demo","",true).search("MySQL",null,0,20)).isInstanceOfSatisfying(SearchClient.Failure.class,ex->assertThat(ex.status).isEqualTo(401));
        mvc.perform(get("/api/v1/public/search").param("q","MySQL","Boot")).andExpect(status().isBadRequest());
        httpStatus=401;mvc.perform(get("/api/v1/public/search").param("q","数据库")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SEARCH_UNAVAILABLE")).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post("/api/v1/admin/search/restore")).andExpect(status().isForbidden());
    }
    @Test void retryAndAckCannotLoseANewerVersion() throws Exception {
        var p=published();httpStatus=503;worker.poll();assertThat(state.diagnostics().toString()).contains("RETRY");
        state.requeue();httpStatus=422;worker.poll();assertThat(state.diagnostics().toString()).contains("ERROR");
        state.requeue();httpStatus=200;started=new CountDownLatch(1);release=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();try {
            var running=executor.submit(worker::poll);assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();
            content.publish(p.id(),new ArticleService.VersionInput(p.version()));release.countDown();running.get(5,TimeUnit.SECONDS);
            assertThat(state.snapshot().getFirst().version()).isEqualTo(2);assertThat(state.diagnostics().toString()).contains("PENDING");
            started=null;worker.poll();assertThat(state.diagnostics().toString()).contains("SYNCED");
        }finally{release.countDown();executor.shutdownNow();}
    }
    @Test void restoreKeepsConcurrentPublishPendingAndFailureDoesNotAck() throws Exception {
        var p=published();started=new CountDownLatch(1);release=new CountDownLatch(1);var executor=Executors.newSingleThreadExecutor();
        try {
            var restore=executor.submit(worker::restore);assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();
            content.publish(p.id(),new ArticleService.VersionInput(p.version()));release.countDown();assertThat(restore.get(5,TimeUnit.SECONDS).get("restored")).isEqualTo(1);
            assertThat(state.snapshot().getFirst().version()).isEqualTo(2);assertThat(state.diagnostics().toString()).contains("PENDING");
            started=null;httpStatus=503;assertThatThrownBy(worker::restore).isInstanceOf(SearchClient.Failure.class);assertThat(state.diagnostics().toString()).contains("PENDING");
            httpStatus=200;worker.poll();assertThat(state.diagnostics().toString()).contains("SYNCED");
        }finally{release.countDown();executor.shutdownNow();}
    }
}
