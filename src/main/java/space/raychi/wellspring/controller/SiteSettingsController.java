package space.raychi.wellspring.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import space.raychi.wellspring.api.ApiResponses;
import space.raychi.wellspring.dto.SiteSettingsDto.Settings;
import space.raychi.wellspring.service.SiteSettingsService;

@RestController
public class SiteSettingsController {
    private final SiteSettingsService settings;
    SiteSettingsController(SiteSettingsService settings) { this.settings = settings; }

    @GetMapping("/api/v1/public/settings")
    ResponseEntity<Settings> publicSettings() { return ApiResponses.ok(settings.publicSettings()); }

    @GetMapping("/api/v1/admin/settings")
    ResponseEntity<Settings> adminSettings() { return ApiResponses.ok(settings.adminSettings()); }

    @PutMapping("/api/v1/admin/settings")
    ResponseEntity<Settings> save(@RequestBody Settings input) { return ApiResponses.ok(settings.save(input)); }
}
