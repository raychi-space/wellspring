package space.raychi.wellspring.reading;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReadingSyncWorker {
    private final ReadingService reading;
    private final ReentrantLock lock = new ReentrantLock();

    public ReadingSyncWorker(ReadingService reading) { this.reading = reading; }

    @Scheduled(scheduler = "readingSyncScheduler", fixedDelayString = "${raychi.reading.sync-ms:14400000}",
            initialDelayString = "${raychi.reading.initial-delay-ms:15000}")
    public void sync() {
        if (!lock.tryLock()) return;
        try {
            try { reading.syncEnabled(); }
            catch (RuntimeException ignored) { /* Per-source state exposes failures; the next interval retries. */ }
        } finally { lock.unlock(); }
    }
}
