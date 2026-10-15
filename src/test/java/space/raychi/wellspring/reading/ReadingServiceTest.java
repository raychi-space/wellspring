package space.raychi.wellspring.reading;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

class ReadingServiceTest {
    @Test
    void concurrentDiscoveryRefreshesTheArticleInsertedByAnotherSync() {
        ReadingMapper data = mock(ReadingMapper.class);
        ReadingSourceClient client = mock(ReadingSourceClient.class);
        ReadingSourceParser parser = mock(ReadingSourceParser.class);
        ReadingService service = new ReadingService(data, client, parser);
        Instant publishedAt = Instant.parse("2026-10-14T00:00:00Z");
        var source = new ReadingModels.Source("openai", "OpenAI", "", "https://developers.openai.com/blog",
                "https://developers.openai.com/blog", "OPENAI_DEVELOPERS", true, 10,
                null, null, null, publishedAt, publishedAt);
        var inserted = new ReadingModels.Article("article", source.id(),
                "https://developers.openai.com/blog/example", "Old title", null,
                publishedAt, publishedAt, publishedAt, "0".repeat(64), "PENDING", null, 0, publishedAt, 0);

        when(data.source(source.id())).thenReturn(Optional.of(source));
        when(data.settings()).thenReturn(new ReadingModels.Settings(0, 90, null));
        when(client.fetch(source)).thenReturn("<html></html>");
        when(parser.parse(eq(source.adapter()), eq(source.listingUrl()), any(), any()))
                .thenReturn(List.of(new ReadingModels.ParsedArticle(inserted.externalUrl(), "New title", publishedAt)));
        when(data.byUrl(inserted.externalUrl())).thenReturn(Optional.empty(), Optional.of(inserted));
        doThrow(new DuplicateKeyException("concurrent insert")).when(data).insertArticle(any());

        service.sync(source.id());

        verify(data).refreshArticle(eq(inserted), eq(source.id()), eq("New title"), eq(publishedAt), any(), any());
        verify(data).syncSuccess(eq(source.id()), any());
    }
}
