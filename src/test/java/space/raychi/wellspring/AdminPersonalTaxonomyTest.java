package space.raychi.wellspring;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import space.raychi.wellspring.dto.*;
import space.raychi.wellspring.mapper.TaxonomyMapper;
import space.raychi.wellspring.service.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:admin_personal;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=", "raychi.admin.username=profile-admin"})
@AutoConfigureMockMvc
class AdminPersonalTaxonomyTest {
    private static final String PASSWORD = "isolated-initial-password";
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("raychi.admin.password-hash", () -> new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(PASSWORD));
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TaxonomyService taxonomy;
    @Autowired TaxonomyMapper catalog;
    @Autowired ArticleService contents;
    @Autowired SiteSettingsService settings;
    @Autowired ContentTrashService trash;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

    @Test
    void usernameRequiresCurrentPasswordAndRevokesEveryOldSession() throws Exception {
        mvc.perform(post("/api/v1/auth/username").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new ChangeUsernameRequest(PASSWORD, "next-admin"))))
            .andExpect(status().isUnauthorized());
        var first = login("profile-admin"); var other = login("profile-admin");
        mvc.perform(post("/api/v1/auth/username").session(first).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new ChangeUsernameRequest(PASSWORD, "next-admin"))))
            .andExpect(status().isForbidden());
        for (var input : List.of(new ChangeUsernameRequest("wrong", "next-admin"),
                new ChangeUsernameRequest(PASSWORD, "bad name"), new ChangeUsernameRequest(PASSWORD, "profile-admin")))
            mvc.perform(post("/api/v1/auth/username").session(first).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(input))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/auth/username").session(first).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new ChangeUsernameRequest(PASSWORD, "next-admin"))))
            .andExpect(status().isNoContent());
        assertThat(first.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/auth/session").session(other)).andExpect(jsonPath("$.authenticated").value(false));
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username","profile-admin","password",PASSWORD))))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/contents").session(login("next-admin"))).andExpect(status().isOk());
    }

    @Test
    void avatarIsValidatedVersionedAtomicAndIndependentOfContentAttachments() throws Exception {
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB), "png", buffer);
        var file = new MockMultipartFile("file", "portrait.png", "image/png", buffer.toByteArray());
        var before = settings.adminSettings();
        mvc.perform(multipart("/api/v1/admin/profile/avatar").file(file).param("version", ""+before.version()).with(csrf()))
            .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/v1/admin/profile/avatar").file(file).param("version", ""+before.version()).with(user("admin")))
            .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/v1/admin/profile/avatar").file(new MockMultipartFile("file", "fake.png", "image/png", "not an image".getBytes()))
            .param("version", ""+before.version()).with(user("admin")).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/v1/admin/profile/avatar").file(new MockMultipartFile("file", "big.png", "image/png", new byte[2*1024*1024+1]))
            .param("version", ""+before.version()).with(user("admin")).with(csrf())).andExpect(status().isPayloadTooLarge());
        mvc.perform(multipart("/api/v1/admin/profile/avatar").file(file).param("version", ""+before.version()).with(user("admin")).with(csrf()))
            .andExpect(status().isOk());
        var saved = settings.publicSettings();
        assertThat(saved.version()).isEqualTo(before.version()+1);
        assertThat(saved.siteName()).isEqualTo(before.siteName());
        assertThat(saved.homepage()).isEqualTo(before.homepage());
        var response = mvc.perform(get(saved.avatarUrl())).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
            .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        var decoded = ImageIO.read(new java.io.ByteArrayInputStream(response.getResponse().getContentAsByteArray()));
        assertThat(decoded.getWidth()).isEqualTo(512);
        assertThat(decoded.getHeight()).isEqualTo(341);
        mvc.perform(head(saved.avatarUrl())).andExpect(status().isOk()).andExpect(content().string(""));
        mvc.perform(multipart("/api/v1/admin/profile/avatar").file(file).param("version", ""+before.version()).with(user("admin")).with(csrf()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SETTINGS_VERSION_CONFLICT"));
        assertThat(settings.publicSettings().avatarUrl()).isEqualTo(saved.avatarUrl());
        mvc.perform(get(saved.avatarUrl())).andExpect(status().isOk());
        mvc.perform(multipart("/api/v1/admin/profile/avatar").file(file).param("version", ""+saved.version()).with(user("admin")).with(csrf()))
            .andExpect(status().isOk());
        mvc.perform(get(saved.avatarUrl())).andExpect(status().isNotFound());
    }

    @Test
    void catalogProtectsDraftPublicTrashAndHistoryAndLeavesPublicSnapshotsUntouched() throws Exception {
        String category = "category-"+shortId(), tag = "tag-"+shortId();
        taxonomy.createCategory(new NameDto(category)); taxonomy.createTag(new NameDto(tag));
        var draft = contents.create("ARTICLE", null);
        var saved = contents.save(draft.id(), new ArticleInput(draft.version(), draft.slug(), "Title", "", "# Title\n\nBody", List.of(tag), null, category));
        var item = taxonomy.management(TaxonomyMapper.Kind.TAG).stream().filter(v -> v.name().equals(tag)).findFirst().orElseThrow();
        assertThat(item.draftCount()).isEqualTo(1); assertThat(item.historyCount()).isEqualTo(1); assertThat(item.referenceCount()).isEqualTo(1);
        mvc.perform(get("/api/v1/admin/taxonomy/tags")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/v1/admin/taxonomy/tags").param("name",tag).with(user("admin"))).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/admin/taxonomy/tags").param("name",tag).with(user("admin")).with(csrf()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TAXONOMY_IN_USE"));
        var published = contents.publish(saved.id(), new VersionInput(saved.version(), null, true));
        contents.save(published.id(), new ArticleInput(published.version(), published.slug(), "Title", "", "# Title\n\nChanged", List.of(), null, "未分类"));
        assertThat(contents.getPublic("ARTICLE", published.slug()).tags()).containsExactly(tag);
        assertThat(contents.getPublic("ARTICLE", published.slug()).category()).isEqualTo(category);
        assertThat(taxonomy.references(TaxonomyMapper.Kind.TAG,tag)).extracting(TaxonomyManagement.Reference::id).containsExactly(saved.id());
        assertThatThrownBy(() -> taxonomy.rename(TaxonomyMapper.Kind.CATEGORY, new TaxonomyManagement.Rename(category,"renamed")))
            .isInstanceOf(space.raychi.wellspring.api.ApiException.class);
        var current = contents.getAdmin(saved.id());
        trash.trash(saved.id(), new VersionInput(current.version()));
        assertThat(taxonomy.management(TaxonomyMapper.Kind.TAG).stream().filter(v -> v.name().equals(tag)).findFirst().orElseThrow().trashCount()).isEqualTo(1);
        assertThatThrownBy(() -> taxonomy.delete(TaxonomyMapper.Kind.TAG,tag)).isInstanceOf(space.raychi.wellspring.api.ApiException.class);
        mvc.perform(delete("/api/v1/admin/taxonomy/categories").param("name","未分类").with(user("admin")).with(csrf()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TAXONOMY_PROTECTED"));
        String unused = "unused-"+shortId();
        taxonomy.createTag(new NameDto(unused));
        mvc.perform(put("/api/v1/admin/taxonomy/tags").with(user("admin")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(new TaxonomyManagement.Rename(unused,unused+"-new")))).andExpect(status().isNoContent());
        taxonomy.delete(TaxonomyMapper.Kind.TAG,unused+"-new");
        assertThat(catalog.exists(TaxonomyMapper.Kind.TAG,unused+"-new")).isFalse();
    }

    @Test
    void referenceCreationAndDeletionSerializeAcrossTransactions() throws Exception {
        String name = "race-"+shortId();
        taxonomy.createTag(new NameDto(name));
        var locked = new java.util.concurrent.CountDownLatch(1);
        var proceed = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var writer = executor.submit(() -> new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> {
                catalog.lock(); locked.countDown();
                try { if (!proceed.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
                return contents.create("POST",new ArticleInput(null,null,"","","reference",List.of(name),null,null));
            }));
            assertThat(locked.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var remover = executor.submit(() -> { taxonomy.delete(TaxonomyMapper.Kind.TAG,name); return true; });
            assertThatThrownBy(() -> remover.get(150,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            proceed.countDown(); writer.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThatThrownBy(() -> remover.get(5,java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(space.raychi.wellspring.api.ApiException.class);
            assertThat(catalog.exists(TaxonomyMapper.Kind.TAG,name)).isTrue();
        } finally { proceed.countDown(); }
    }
    private MockHttpSession login(String username) throws Exception {
        return (MockHttpSession)mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username",username,"password",PASSWORD))))
            .andExpect(status().isOk()).andReturn().getRequest().getSession(false);
    }
    private static String shortId() { return UUID.randomUUID().toString().substring(0,8); }
}
