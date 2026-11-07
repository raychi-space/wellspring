package space.raychi.wellspring.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import space.raychi.wellspring.service.ArticleService;

class AnalyticsQueueTest {
    @Test void disablingDropsWaitingEventsBeforeTheyReachTheCollector() throws Exception {
        var json = new ObjectMapper();
        var client = spy(new AnalyticsClient(json, "http://127.0.0.1:1", "fixture", "private-fixture-token", true));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return false; }).when(client).collect(any());
        var settings = mock(AnalyticsSettingsService.class);
        when(settings.collectionEnabled()).thenReturn(true);
        var service = new AnalyticsService(client, mock(ArticleService.class), settings, "https://fixture.invalid");
        try {
            var event = json.createObjectNode().put("id", UUID.randomUUID().toString()).put("path", "/")
                    .put("occurredAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
            assertThat(service.collect(event, "https://fixture.invalid", "Mozilla/5.0", false, new AnalyticsService.SessionBudget())).isEqualTo("queued");
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(service.collect(event, "https://fixture.invalid", "Mozilla/5.0", false, new AnalyticsService.SessionBudget())).isEqualTo("queued");
            when(settings.collectionEnabled()).thenReturn(false);
            release.countDown();
            long deadline = System.currentTimeMillis() + 3000;
            while ((long) service.status().get("dropped") < 2 && System.currentTimeMillis() < deadline) Thread.sleep(10);
            verify(client, times(1)).collect(any());
            assertThat(service.status().get("dropped")).isEqualTo(2L);
            assertThat(service.status().get("queued")).isEqualTo(0);
        } finally { release.countDown(); service.stop(); }
    }

    @Test void slowCollectorHasBoundedQueueAndNeverRunsOnRequestThread() throws Exception {
        var json = new ObjectMapper();
        var client = spy(new AnalyticsClient(json, "http://127.0.0.1:1", "fixture", "private-fixture-token", true));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); release.await(5, TimeUnit.SECONDS); return false; }).when(client).collect(any());
        var settings = mock(AnalyticsSettingsService.class);
        when(settings.collectionEnabled()).thenReturn(true);
        var service = new AnalyticsService(client, mock(ArticleService.class), settings, "https://fixture.invalid");
        try {
            var event = json.createObjectNode().put("id", UUID.randomUUID().toString()).put("path", "/")
                    .put("occurredAt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
            assertThat(service.collect(event, "https://fixture.invalid", "Mozilla/5.0", false, new AnalyticsService.SessionBudget())).isEqualTo("queued");
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            int queued = 0, dropped = 0;
            for (int n = 0; n < 300; n++) {
                String result = service.collect(event, "https://fixture.invalid", "Mozilla/5.0", false, new AnalyticsService.SessionBudget());
                if (result.equals("queued")) queued++; else dropped++;
            }
            assertThat(queued).isEqualTo(256); assertThat(dropped).isEqualTo(44);
            assertThat(service.status().get("queued")).isEqualTo(256);
            assertThat(service.status().get("dropped")).isEqualTo(44L);
        } finally { release.countDown(); service.stop(); }
    }
}
