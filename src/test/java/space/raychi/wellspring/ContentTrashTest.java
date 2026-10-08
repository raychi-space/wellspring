package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.dto.*;
import space.raychi.wellspring.mapper.*;
import space.raychi.wellspring.service.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:trash;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa","spring.datasource.password=","raychi.admin.username=test-admin",
    "raychi.assets.dir=./target/trash-assets"})
@AutoConfigureMockMvc
class ContentTrashTest {
    @DynamicPropertySource static void config(DynamicPropertyRegistry registry) {
        registry.add("raychi.admin.password-hash",()->new BCryptPasswordEncoder().encode("test-password"));
        String mysql=System.getenv("RAYCHI_TEST_PUBLICATION_MYSQL_URL");
        if(mysql!=null&&!mysql.isBlank()) {
            registry.add("spring.datasource.url",()->mysql);
            registry.add("spring.datasource.username",()->System.getenv("RAYCHI_TEST_MYSQL_USER"));
            registry.add("spring.datasource.password",()->System.getenv("RAYCHI_TEST_MYSQL_PASSWORD"));
        }
    }
    @Autowired ArticleService contents;
    @Autowired ContentTrashService trash;
    @Autowired ContentHistoryService history;
    @Autowired ContentExportService exports;
    @Autowired AssetService assets;
    @Autowired SearchVisibilityMapper visibility;
    @Autowired RelatedArticlesService related;
    @Autowired JdbcTemplate db;
    @Autowired MockMvc mvc;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean ArticleMapper articles;
    @MockitoSpyBean ContentRevisionMapper revisions;
    private final List<String> ids=new ArrayList<>();
    private final List<Path> files=new ArrayList<>();
    @AfterEach void cleanup() throws Exception {
        reset(articles,revisions);
        for(String id:ids) {
            var row=articles.selectById(id,false).orElse(null);
            if(row==null) continue;
            if(row.status().equals("TRASHED")) row=articles.selectById(trash.restore(id,new VersionInput(row.version())).id(),false).orElseThrow();
            contents.delete(id,new VersionInput(row.version()));
        }
        for(Path file:files) Files.deleteIfExists(file);
    }
    @Test void preservesDraftPublicationImagesHistoryAndRestoresPrivatelyWithSearchTombstone() throws Exception {
        var target=save(create("ARTICLE"),"关联文章",null);
        target=contents.publish(target.id(),new VersionInput(target.version(),null,true));
        for(String type:List.of("ARTICLE","POST")) {
            var item=create(type);
            UploadedAsset image=type.equals("ARTICLE")?upload(item.id()):null;
            item=save(item,"公开版本",image);
            item=contents.publish(item.id(),new VersionInput(item.version(),null,true));
            var published=contents.getPublic(type,item.slug());
            item=save(item,"私有工作稿",image);
            long before=history.list(item.id(),1,20).total();
            var moved=trash.trash(item.id(),new VersionInput(item.version()));
            assertThat(moved.version()).isEqualTo(item.version()+1);
            assertThat(moved.trashedAt()).isNotNull();
            final String id=item.id(),slug=item.slug();
            assertThatThrownBy(()->contents.getAdmin(id)).isInstanceOf(ApiException.class);
            assertThatThrownBy(()->contents.getPublic(type,slug)).isInstanceOf(ApiException.class);
            assertThatThrownBy(()->history.list(id,1,20)).isInstanceOf(ApiException.class);
            assertThatThrownBy(()->exports.export(id,moved.version())).isInstanceOf(ApiException.class);
            assertThat(contents.listAdmin(1,50,null,null).items()).extracting(AdminArticle::id).doesNotContain(id);
            assertThat(contents.listPublic(1,50,null,null,null).items()).extracting(PublicArticle::id).doesNotContain(id);
            assertThat(visibility.findPublished(List.of("content:"+id))).isEmpty();
            assertThat(db.queryForObject("SELECT desired_action FROM search_sync_state WHERE document_id=?",String.class,"content:"+id)).isEqualTo("DELETE");
            assertThat(related.articles(target.slug(),6)).extracting(RelatedArticle::id).doesNotContain(id);
            if(image!=null) {
                final UploadedAsset owned=image;
                assertThat(assets.read(image.id(),true).bytes()).isNotEmpty();
                assertThatThrownBy(()->assets.read(owned.id(),false)).isInstanceOf(ApiException.class);
                assertThatThrownBy(()->assets.upload(id,png())).isInstanceOf(ApiException.class);
            }
            var restored=trash.restore(id,new VersionInput(moved.version()));
            assertThat(restored.status()).isEqualTo("DRAFT");
            assertThat(restored.version()).isEqualTo(item.version()+2);
            assertThat(restored.bodyMarkdown()).isEqualTo(item.bodyMarkdown());
            assertThat(restored.slug()).isEqualTo(item.slug());
            assertThat(restored.publishedAt()).isEqualTo(item.publishedAt());
            assertThat(articles.selectById(id,false).orElseThrow().publicBody()).isEqualTo(published.bodyMarkdown());
            assertThat(history.list(id,1,20).total()).isEqualTo(before+2);
            assertThat(history.list(id,1,20).items().getFirst().operation()).isEqualTo("RECOVER");
            assertThat(visibility.findPublished(List.of("content:"+id))).isEmpty();
            assertThatThrownBy(()->contents.getPublic(type,slug)).isInstanceOf(ApiException.class);
            restored=contents.publish(id,new VersionInput(restored.version(),null,true));
            assertThat(contents.getPublic(type,slug).bodyMarkdown()).contains("私有工作稿");
            if(image!=null) assertThat(assets.read(image.id(),false).bytes()).isNotEmpty();
        }
    }
    @Test void historyFailureAndPurgeFailureRollbackContentRevisionsAssetsAndQueue() throws Exception {
        var item=create("ARTICLE");
        var image=upload(item.id());
        item=save(item,"原稿",image);
        String id=item.id();long version=item.version();long count=history.list(id,1,20).total();
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("intentional revision failure");}).when(revisions).insert(any());
        assertThatThrownBy(()->trash.trash(id,new VersionInput(version))).hasMessage("intentional revision failure");
        reset(revisions);
        assertThat(contents.getAdmin(id).version()).isEqualTo(version);
        assertThat(history.list(id,1,20).total()).isEqualTo(count);
        var moved=trash.trash(id,new VersionInput(version));
        doAnswer(call->{call.callRealMethod();throw new IllegalStateException("intentional delete failure");}).when(articles).delete(id);
        assertThatThrownBy(()->trash.purge(id,new VersionInput(moved.version()))).hasMessage("intentional delete failure");
        reset(articles);
        assertThat(articles.selectById(id,false).orElseThrow().status()).isEqualTo("TRASHED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM assets WHERE id=?",Long.class,image.id())).isOne();
        String key=image.id().replace("-","");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM asset_deletion_queue WHERE storage_key=?",Long.class,key)).isZero();
        assertThat(Files.exists(Path.of("target/trash-assets",key))).isTrue();
        trash.purge(id,new VersionInput(moved.version()));
        assertThat(articles.selectById(id,false)).isEmpty();
        assertThat(revisions.count(id)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM assets WHERE article_id=?",Long.class,id)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM asset_deletion_queue WHERE storage_key=?",Long.class,key)).isOne();
    }
    @Test void uploadOuterRollbackRemovesNewFileAndMetadata() throws Exception {
        var item=create("ARTICLE");
        var template=new TransactionTemplate(transactions);
        final UploadedAsset[] result=new UploadedAsset[1];
        template.execute(status->{try{result[0]=upload(item.id());}catch(Exception ex){throw new RuntimeException(ex);}status.setRollbackOnly();return null;});
        assertThat(Files.exists(Path.of("target/trash-assets",result[0].id().replace("-","")))).isFalse();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM assets WHERE id=?",Long.class,result[0].id())).isZero();
    }
    @Test void concurrentRecoveryCommitsOnceAndPaginationIsBounded() throws Exception {
        var first=create("POST");
        var moved=trash.trash(first.id(),new VersionInput(first.version()));
        var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Integer> call=()->{start.await();try{trash.restore(first.id(),new VersionInput(moved.version()));return 200;}catch(ApiException ex){return ex.status().value();}};
            var a=executor.submit(call);var b=executor.submit(call);start.countDown();
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
        }
        assertThat(contents.getAdmin(first.id()).version()).isEqualTo(first.version()+2);
        for(int i=0;i<21;i++){var row=create("POST");trash.trash(row.id(),new VersionInput(row.version()));}
        assertThat(trash.list(1,20).items()).hasSize(20);
        assertThat(trash.list(2,20).items()).hasSize(1);
        assertThat(trash.list(3,20).items()).isEmpty();
        assertThatThrownBy(()->trash.list(1,51)).isInstanceOf(ApiException.class);
    }
    @Test void httpAuthCsrfParametersVersionAndTrashOnlyPurge() throws Exception {
        var row=create("POST");String id=row.id();String move="/api/v1/admin/contents/"+id+"/trash";
        mvc.perform(get("/api/v1/admin/trash")).andExpect(status().isUnauthorized());
        mvc.perform(post(move).with(user("owner")).contentType("application/json").content("{\"expectedVersion\":0}")).andExpect(status().isForbidden());
        mvc.perform(post(move).with(user("owner")).with(csrf()).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post(move).with(user("owner")).with(csrf()).contentType("application/json").content("{\"expectedVersion\":-1}")).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/admin/trash/"+id+"?expectedVersion=0").with(user("owner")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post(move).with(user("owner")).with(csrf()).contentType("application/json").content("{\"expectedVersion\":0}")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(get("/api/v1/admin/trash").with(user("owner"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(header().exists("X-Request-Id")).andExpect(jsonPath("$.items[0].bodyMarkdown").doesNotExist());
        mvc.perform(get("/api/v1/admin/trash?page=0").with(user("owner"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/contents/"+id).with(user("owner"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/trash/"+id+"/restore").with(user("owner")).with(csrf()).contentType("application/json").content("{\"expectedVersion\":0}")).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/admin/trash/"+id+"?expectedVersion=1").with(user("owner"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/trash/"+id+"?expectedVersion=-1").with(user("owner")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(delete("/api/v1/admin/trash/"+id+"?expectedVersion=1").with(user("owner")).with(csrf())).andExpect(status().isNoContent()).andExpect(header().string("Cache-Control","no-store"));
    }
    private AdminArticle create(String type){var row=contents.create(type,null);ids.add(row.id());return row;}
    private AdminArticle save(AdminArticle row,String title,UploadedAsset image){return contents.save(row.id(),new ArticleInput(row.version(),row.slug(),title,title+"摘要","# "+title+"\n\n正文"+(image==null?"":"\n\n![图]("+image.url()+")"),List.of("回收验收"),image==null?null:image.url(),row.category(),true));}
    private MockMultipartFile png() throws Exception {var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);return new MockMultipartFile("file","x.png","image/png",bytes.toByteArray());}
    private UploadedAsset upload(String id) throws Exception {var image=assets.upload(id,png());files.add(Path.of("target/trash-assets",image.id().replace("-","")));return image;}
}
