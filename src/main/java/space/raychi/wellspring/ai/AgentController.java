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

  public AgentController(AgentClient client, WritingService writing) {
    this.client = client;
    this.writing = writing;
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

  @GetMapping("/turns/{id}")
  JsonNode turn(@PathVariable String id, Principal principal) {
    return writing.get(id, principal.getName());
  }
}
