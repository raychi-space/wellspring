package space.raychi.wellspring.ai;

import com.fasterxml.jackson.databind.JsonNode;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/ai")
@PreAuthorize("hasRole('ADMIN')")
public class AgentController {
  private final AgentClient client;
  private final WritingService writing;
  private final WritingStreams streams;
  private final space.raychi.wellspring.service.AdminAccountService accounts;

  public AgentController(AgentClient client, WritingService writing, WritingStreams streams, space.raychi.wellspring.service.AdminAccountService accounts) {
    this.client = client;
    this.writing = writing;
    this.streams = streams;
    this.accounts = accounts;
  }

  @GetMapping("/providers")
  JsonNode providers() {
    return client.request("GET", "/providers", null, null);
  }

  @PostMapping("/providers")
  @ResponseStatus(HttpStatus.CREATED)
  JsonNode provider(@RequestBody JsonNode body) {
    return client.request("POST", "/providers", body, null);
  }

  @PatchMapping("/providers/{id}")
  JsonNode provider(@PathVariable String id, @RequestBody JsonNode body) {
    return client.request("PATCH", "/providers/" + WritingService.id(id), body, null);
  }

  @PostMapping("/providers/{id}/test")
  JsonNode test(@PathVariable String id, @RequestBody JsonNode body) {
    return client.request("POST", "/providers/" + WritingService.id(id) + "/test", body, null);
  }

  @GetMapping("/assistants")
  JsonNode assistants() {
    return client.request("GET", "/assistants", null, null);
  }

  @PostMapping("/assistants")
  @ResponseStatus(HttpStatus.CREATED)
  JsonNode assistant(@RequestBody JsonNode body) {
    return client.request("POST", "/assistants", body, null);
  }

  @PatchMapping("/assistants/{id}")
  JsonNode assistant(@PathVariable String id, @RequestBody JsonNode body) {
    return client.request("PATCH", "/assistants/" + WritingService.id(id), body, null);
  }

  @PostMapping("/turns")
  @ResponseStatus(HttpStatus.ACCEPTED)
  JsonNode turn(@RequestBody JsonNode body, Principal principal) {
    return writing.create(body, principal.getName());
  }

  @GetMapping(value = "/turns/{id}/events", produces = "text/event-stream")
  org.springframework.web.servlet.mvc.method.annotation.SseEmitter events(@PathVariable String id, Principal principal, jakarta.servlet.http.HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response) {
    if (!(principal instanceof org.springframework.security.core.Authentication auth)
        || !(auth.getPrincipal() instanceof space.raychi.wellspring.service.AdminPrincipal owner))
      throw AgentClient.error(401, "AUTH_REQUIRED");
    var initial = writing.get(id, principal.getName()); // Check task ownership before opening a stream.
    var session = request.getSession(false);
    response.setHeader("Cache-Control", "no-store, no-cache, no-transform");
    response.setHeader("X-Accel-Buffering", "no");
    return streams.open(() -> {
      if (session == null) throw AgentClient.error(401, "AUTH_REQUIRED");
      try { session.getCreationTime(); } catch (IllegalStateException ex) { throw AgentClient.error(401, "AUTH_REQUIRED"); }
      if (!accounts.isCurrent(owner)) throw AgentClient.error(401, "AUTH_REQUIRED");
      return writing.get(id, principal.getName());
    }, initial);
  }

  @GetMapping("/turns/{id}")
  JsonNode turn(@PathVariable String id, Principal principal) {
    return writing.get(id, principal.getName());
  }
}
