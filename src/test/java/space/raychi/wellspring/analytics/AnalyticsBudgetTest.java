package space.raychi.wellspring.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class AnalyticsBudgetTest {
    @Test void sixtyEventsPerFixedMinuteResetExactlyAtBoundaryWithIndependentSessions() {
        var millis = new AtomicLong(59_999);
        var first = new AnalyticsService.SessionBudget(millis::get);
        var second = new AnalyticsService.SessionBudget(millis::get);
        for (int i = 0; i < 60; i++) assertThat(first.accept()).isTrue();
        assertThat(first.accept()).isFalse();
        assertThat(second.accept()).isTrue();
        millis.set(60_000);
        for (int i = 0; i < 60; i++) assertThat(first.accept()).isTrue();
        assertThat(first.accept()).isFalse();
        assertThat(second.accept()).isTrue();
    }
}
