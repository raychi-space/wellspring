package space.raychi.wellspring;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import space.raychi.wellspring.tools.HistoricalContentImport;
import space.raychi.wellspring.tools.HistoricalContentImport.Entry;
import space.raychi.wellspring.tools.HistoricalContentImport.Manifest;

@EnabledIfEnvironmentVariable(named="RAYCHI_TEST_POSTGRES_URL", matches="jdbc:postgresql://[^/]+/[a-zA-Z0-9_]+_test")
class HistoricalContentImportTest {
    private Connection database() throws Exception {
        String url=System.getenv("RAYCHI_TEST_POSTGRES_URL"), user=System.getenv("RAYCHI_TEST_POSTGRES_USER"),
                password=System.getenv("RAYCHI_TEST_POSTGRES_PASSWORD");
        String schema="historical_"+UUID.randomUUID().toString().replace("-", "");
        try(var c=DriverManager.getConnection(url,user,password)) { c.createStatement().execute("CREATE SCHEMA "+schema); }
        String isolated=url+"?currentSchema="+schema;
        Flyway.configure().dataSource(isolated,user,password).locations("classpath:db/postgresql").load().migrate();
        return DriverManager.getConnection(isolated,user,password);
    }
    private Entry entry(String path,String slug,String body) throws Exception {
        return new Entry(path,slug,"历史标题","摘要",body,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8))),"软件开发",List.of("Git","历史迁移"),
                "2024-11-10T16:00:00Z","2025-01-01T00:00:00Z");
    }
    private Manifest manifest(Entry... entries) {return new Manifest(1,"https://legacy.example",List.of(entries));}
    private long count(Connection c,String table) throws Exception {
        try(var r=c.createStatement().executeQuery("SELECT count(*) FROM "+table)){r.next();return r.getLong(1);}
    }
    @Test void checkRollsBackAndApplyPreservesDatesOutboxTagsAndHistory() throws Exception {
        try(var c=database()){
            var m=manifest(entry("/git/","git","# 正文\n\n中文😀 `git status`"));
            assertEquals(1,HistoricalContentImport.run(c,m,"--check").inserted());
            assertEquals(0,count(c,"articles"));assertEquals(0,count(c,"search_sync_state"));
            assertEquals(1,HistoricalContentImport.run(c,m,"--apply").inserted());
            assertEquals(1,count(c,"content_revisions"));assertEquals(2,count(c,"public_content_tags"));
            try(var r=c.createStatement().executeQuery("SELECT desired_action,status,version FROM search_sync_state")){
                r.next();assertEquals("UPSERT",r.getString(1));assertEquals("PENDING",r.getString(2));assertEquals(1,r.getLong(3));
            }
            assertEquals(1,HistoricalContentImport.run(c,m,"--verify").unchanged());
            assertEquals(1,HistoricalContentImport.run(c,m,"--apply").unchanged());
            assertEquals(1,count(c,"articles"));assertEquals(1,count(c,"content_revisions"));
        }
    }
    @Test void changedImportRejectsOverwriteAndRollsBackEarlierNewEntries() throws Exception {
        try(var c=database()){
            HistoricalContentImport.run(c,manifest(entry("/git/","git","original")),"--apply");
            var changed=manifest(entry("/new/","new","new"),entry("/git/","git","changed"));
            assertThrows(IllegalStateException.class,()->HistoricalContentImport.run(c,changed,"--apply"));
            assertEquals(1,count(c,"articles"));assertEquals(1,count(c,"search_sync_state"));
        }
    }
    @Test void distinctSourcesCannotOverwriteExistingSlugOrClaimEditedContent() throws Exception {
        try(var c=database()){
            HistoricalContentImport.run(c,manifest(entry("/one/","same","original")),"--apply");
            assertThrows(IllegalStateException.class,()->HistoricalContentImport.run(c,manifest(entry("/two/","same","other")),"--apply"));
            c.createStatement().executeUpdate("UPDATE articles SET draft_body='later draft',version=version+1");
            assertThrows(IllegalStateException.class,()->HistoricalContentImport.run(c,manifest(entry("/one/","same","original")),"--verify"));
            assertEquals(1,count(c,"articles"));
        }
    }
    @Test void invalidDigestsDuplicatesImagesAndHtmlAreRejectedBeforeAnyWrite() throws Exception {
        try(var c=database()){
            var valid=entry("/one/","one","original");
            var broken=new Entry(valid.sourcePath(),valid.slug(),valid.title(),valid.summary(),valid.bodyMarkdown(),"0".repeat(64),valid.category(),valid.tags(),valid.publishedAt(),valid.modifiedAt());
            for(var m:List.of(manifest(broken),manifest(valid,valid),manifest(entry("/image/","image","![missing](image.png)")),manifest(entry("/html/","html","<img src='x'>"))))
                assertThrows(IllegalArgumentException.class,()->HistoricalContentImport.run(c,m,"--apply"));
            assertEquals(0,count(c,"articles"));
        }
    }
    @Test void verifyNeverCreatesMissingRowsAndUnrelatedDataIsPreserved() throws Exception {
        try(var c=database()){
            var one=manifest(entry("/one/","one","original"));
            assertThrows(IllegalStateException.class,()->HistoricalContentImport.run(c,one,"--verify"));
            long settings=count(c,"site_settings"),sources=count(c,"reading_sources");
            HistoricalContentImport.run(c,one,"--apply");
            assertEquals(settings,count(c,"site_settings"));assertEquals(sources,count(c,"reading_sources"));
            assertEquals(0,count(c,"admin_account"));assertEquals(0,count(c,"assets"));
        }
    }
}
