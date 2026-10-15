package space.raychi.wellspring.reading;

import java.time.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import space.raychi.wellspring.ai.TranslationService;
import space.raychi.wellspring.api.ApiException;

@Component
public class ReadingTranslationWorker {
    private final ReadingMapper data;
    private final TranslationService translations;
    private final ReentrantLock lock = new ReentrantLock();

    public ReadingTranslationWorker(ReadingMapper data, TranslationService translations) {
        this.data = data; this.translations = translations;
    }

    @Scheduled(scheduler = "readingTranslationScheduler", fixedDelayString = "${raychi.reading.translation-poll-ms:2000}")
    public void poll() {
        if (!lock.tryLock()) return;
        try {
            String assistantId = data.settings().translationAssistantId();
            if (assistantId == null || assistantId.isBlank()) return;
            var running = data.runningTranslation();
            if (running.isPresent()) advance(running.get());
            else data.nextTranslation(Instant.now()).ifPresent(article -> start(article, assistantId));
        } finally { lock.unlock(); }
    }

    private void start(ReadingModels.Article article, String assistantId) {
        try {
            String taskId = translations.startTitleTranslation(assistantId, article.id(), article.originalTitle(), article.titleHash());
            data.translationRunning(article.id(), taskId, Instant.now());
        } catch (ApiException ex) { failed(article); }
        catch (RuntimeException ex) { failed(article); }
    }

    private void advance(ReadingModels.Article article) {
        try {
            TranslationService.Task task = translations.task(article.translationTaskId());
            if ("succeeded".equals(task.status()))
                data.translationSucceeded(article.id(), task.translatedTitle(), Instant.now());
            else if ("failed".equals(task.status())) failed(article);
            else if (article.updatedAt().isBefore(Instant.now().minus(Duration.ofMinutes(3)))) failed(article);
        } catch (ApiException ex) { failed(article); }
        catch (RuntimeException ex) { failed(article); }
    }

    private void failed(ReadingModels.Article article) {
        int attempt = article.translationAttempts() + 1;
        long seconds = switch (attempt) { case 1 -> 60; case 2 -> 300; case 3 -> 1800; default -> 21600; };
        Instant now = Instant.now();
        data.translationFailed(article.id(), now.plusSeconds(seconds), now);
    }
}
