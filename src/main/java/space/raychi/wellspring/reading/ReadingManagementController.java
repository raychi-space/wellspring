package space.raychi.wellspring.reading;

import static space.raychi.wellspring.reading.ReadingManagementModels.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import space.raychi.wellspring.api.ApiResponses;

/** All routes inherit the site's authenticated /admin/** and CSRF guards. */
@RestController
@RequestMapping("/api/v1/admin/reading/aihot")
public class ReadingManagementController {
    private final ReadingManagementService service;
    public ReadingManagementController(ReadingManagementService service) { this.service=service; }
    @GetMapping("/dashboard") ResponseEntity<Dashboard> dashboard(@RequestParam Map<String,String> q) { return ApiResponses.okNoStore(service.dashboard(q)); }
    @GetMapping("/sources") ResponseEntity<Sources> sources(@RequestParam Map<String,String> q) { return ApiResponses.okNoStore(service.sources(q)); }
    @GetMapping("/sources/{id}") ResponseEntity<Source> source(@PathVariable String id) { return ApiResponses.okNoStore(service.source(id)); }
    @PostMapping("/sources") ResponseEntity<Source> create(@Valid @RequestBody SourceCreate input) { return ApiResponses.created(service.create(input)); }
    @PutMapping("/sources/{id}") ResponseEntity<Source> update(@PathVariable String id,@Valid @RequestBody SourceUpdate input) { return ApiResponses.okNoStore(service.update(id,input)); }
    @PostMapping("/sources/{id}/check") ResponseEntity<Check> check(@PathVariable String id) { return ApiResponses.okNoStore(service.check(id)); }
    @PostMapping("/sources/{id}/collect") ResponseEntity<Collection> collect(@PathVariable String id) { return ResponseEntity.accepted().header("Cache-Control","no-store").body(service.collect(id)); }
    @GetMapping("/models") ResponseEntity<Models> models() { return ApiResponses.okNoStore(service.models()); }
    @PutMapping("/models/{capability}") ResponseEntity<Models> model(@PathVariable String capability,@RequestBody JsonNode input) { return ApiResponses.okNoStore(service.switchModel(capability,input)); }
    @PutMapping("/budgets/{name}") ResponseEntity<Models> budget(@PathVariable String name,@Valid @RequestBody BudgetInput input) { return ApiResponses.okNoStore(service.budget(name,input)); }
}
