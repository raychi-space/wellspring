package space.raychi.wellspring.reading;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
class ReadingScheduling {
    @Bean
    ThreadPoolTaskScheduler readingSyncScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("reading-sync-");
        return scheduler;
    }

    @Bean
    ThreadPoolTaskScheduler readingTranslationScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("reading-translation-");
        return scheduler;
    }
}
