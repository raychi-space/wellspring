package space.raychi.wellspring.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.multipart.MultipartFile;
import space.raychi.wellspring.api.ApiResponses;
import space.raychi.wellspring.dto.SiteSettingsDto.Settings;
import space.raychi.wellspring.service.ProfileAvatarService;

@RestController
public class ProfileAvatarController {
    private final ProfileAvatarService avatars;
    public ProfileAvatarController(ProfileAvatarService avatars) { this.avatars = avatars; }
    @PostMapping(path="/api/v1/admin/profile/avatar", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Settings> save(@RequestParam("file") MultipartFile file, @RequestParam("version") long version) {
        return ApiResponses.ok(avatars.save(file, version));
    }
    @RequestMapping(path="/api/v1/public/avatar/{id}/content", method={RequestMethod.GET, RequestMethod.HEAD})
    ResponseEntity<byte[]> read(@PathVariable String id, HttpServletRequest request) {
        return ApiResponses.binary(avatars.read(id), "image/png", request.getMethod().equals("HEAD"));
    }
}
