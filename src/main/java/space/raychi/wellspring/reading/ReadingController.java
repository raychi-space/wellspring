package space.raychi.wellspring.reading;

import static space.raychi.wellspring.reading.ReadingModels.*;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import space.raychi.wellspring.api.ApiResponses;

@RestController
public class ReadingController {
    private final ReadingService reading;

    public ReadingController(ReadingService reading) { this.reading = reading; }

    @GetMapping("/api/v1/public/reading")
    ResponseEntity<PublicShelf> publicShelf() { return ApiResponses.ok(reading.publicShelf()); }

    @GetMapping("/api/v1/admin/reading")
    ResponseEntity<AdminShelf> adminShelf() { return ApiResponses.okNoStore(reading.adminShelf()); }

    @PutMapping("/api/v1/admin/reading/settings")
    ResponseEntity<Settings> settings(@RequestBody SettingsInput input) {
        return ApiResponses.okNoStore(reading.saveSettings(input));
    }

    @PostMapping("/api/v1/admin/reading/sources")
    ResponseEntity<AdminSource> create(@RequestBody SourceInput input) {
        return ApiResponses.created(reading.create(input));
    }

    @PutMapping("/api/v1/admin/reading/sources/{id}")
    ResponseEntity<AdminSource> update(@PathVariable String id, @RequestBody SourceInput input) {
        return ApiResponses.okNoStore(reading.update(id, input));
    }

    @DeleteMapping("/api/v1/admin/reading/sources/{id}")
    ResponseEntity<Void> delete(@PathVariable String id) {
        reading.delete(id); return ApiResponses.noContentNoStore();
    }

    @PostMapping("/api/v1/admin/reading/sources/{id}/sync")
    ResponseEntity<AdminSource> sync(@PathVariable String id) {
        return ApiResponses.okNoStore(reading.sync(id));
    }
}
