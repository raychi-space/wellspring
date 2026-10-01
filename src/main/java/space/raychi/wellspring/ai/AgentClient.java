package space.raychi.wellspring.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import space.raychi.wellspring.api.ApiException;

@Component
public class AgentClient {
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
  private final ObjectMapper json;
  private final String base, token;
  private final boolean enabled;

  public AgentClient(
      ObjectMapper json,
      @Value("${raychi.agent.url:http://127.0.0.1:8092}") String base,
      @Value("${raychi.agent.token:}") String token,
      @Value("${raychi.agent.enabled:false}") boolean enabled) {
    this.json = json;
    this.base = base.replaceAll("/$", "");
    this.token = token;
    this.enabled = enabled;
    URI uri = URI.create(base);
    if (!Set.of("http", "https").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) throw new IllegalStateException("Invalid Agent endpoint");
  }

  public JsonNode request(String method, String path, JsonNode body, String idempotencyKey) {
    if (!enabled || token.length() < 16) throw error(503, "AGENT_UNAVAILABLE");
    try {
      var builder =
          HttpRequest.newBuilder(URI.create(base + "/v1" + path))
              .timeout(Duration.ofSeconds(path.endsWith("/test") ? 25 : 5))
              .header("Authorization", "Bearer " + token)
              .header("Content-Type", "application/json");
      if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
      builder.method(
          method,
          body == null
              ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
      // Bound both header wait and body completion; a stalled body cannot occupy a request forever.
      var future = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
      java.net.http.HttpResponse<byte[]> response;
      try {
        response =
            future.get(path.endsWith("/test") ? 25 : 5, java.util.concurrent.TimeUnit.SECONDS);
      } catch (Exception ex) {
        future.cancel(true);
        throw ex;
      }
      byte[] bytes = response.body();
      if (bytes.length > 1048576) throw error(502, "AGENT_PROTOCOL_ERROR");
      JsonNode data = json.readTree(bytes);
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        String code = data == null ? "AGENT_UNAVAILABLE" : data.path("error").path("code").asText();
        if (!Set.of(
                "INVALID_INPUT",
                "INVALID_SCHEMA",
                "CONFIG_UNAVAILABLE",
                "CONTEXT_LIMIT",
                "IDEMPOTENCY_CONFLICT",
                "QUEUE_FULL",
                "NOT_FOUND",
                "SECRET_STORAGE_UNAVAILABLE")
            .contains(code)) throw error(503, "AGENT_UNAVAILABLE");
        throw error(response.statusCode(), code);
      }
      if (data == null || !data.isObject()) throw error(502, "AGENT_PROTOCOL_ERROR");
      return data;
    } catch (ApiException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw error(503, "AGENT_UNAVAILABLE");
    } catch (Exception ex) {
      throw error(503, "AGENT_UNAVAILABLE");
    }
  }

  static ApiException error(int status, String code) {
    String message =
        switch (code) {
          case "CONTEXT_LIMIT" -> "上下文超过助手预算，请减少内容；全文未被截断。";
          case "CONFIG_UNAVAILABLE" -> "助手或模型服务商未启用、密钥未配置或模型不可用。";
          case "SECRET_STORAGE_UNAVAILABLE" -> "Agent 服务尚未配置密钥存储加密密钥。";
          case "IDEMPOTENCY_CONFLICT" -> "本次提交标识已用于另一请求，请重新提交。";
          case "NOT_FOUND" -> "任务或配置不存在。";
          case "INVALID_INPUT", "INVALID_SCHEMA" -> "请检查助手配置或请求内容。";
          case "QUEUE_FULL" -> "任务队列已满，请稍后重试。";
          default -> "Agent 服务暂时不可用，请稍后重试。";
        };
    return new ApiException(HttpStatus.valueOf(status), code, message);
  }
}
