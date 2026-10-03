package space.raychi.wellspring.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bounded bridge for live provider output; streams never contain tool arguments or credentials. */
@Component
public class WritingStreams {
  private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(4);
  private final Semaphore slots = new Semaphore(8);
  @jakarta.annotation.PreDestroy void stop() { executor.shutdownNow(); }
  public SseEmitter open(Supplier<JsonNode> read, JsonNode initial) {
    if (!slots.tryAcquire()) throw AgentClient.error(429, "QUEUE_FULL");
    var emitter = new SseEmitter(180000L);
    var closed = new AtomicBoolean();
    var future = new AtomicReference<ScheduledFuture<?>>();
    Runnable close = () -> { if (closed.compareAndSet(false, true)) { slots.release(); var f = future.get(); if (f != null) f.cancel(true); } };
    emitter.onCompletion(close); emitter.onTimeout(close); emitter.onError(error -> close.run());
    var previous = new AtomicReference<JsonNode>();
    var first = new AtomicBoolean(true);
    Runnable tick = () -> {
      if (closed.get()) return;
      try {
        JsonNode value = first.getAndSet(false) ? initial : read.get();
        if (!value.equals(previous.get())) {
          emitter.send(SseEmitter.event().name("turn").data(value));
          previous.set(value);
        } else emitter.send(SseEmitter.event().comment("keep-alive"));
        String status = value.path("status").asText();
        if (status.equals("succeeded") || status.equals("failed")) { emitter.complete(); close.run(); }
      } catch (Exception error) {
        try { emitter.send(SseEmitter.event().name("stream-error").data("连接中断，请重试。")); } catch (Exception ignored) { }
        emitter.complete(); close.run();
      }
    };
    future.set(executor.scheduleWithFixedDelay(tick, 0, 150, TimeUnit.MILLISECONDS));
    if (closed.get()) future.get().cancel(true);
    return emitter;
  }
}
