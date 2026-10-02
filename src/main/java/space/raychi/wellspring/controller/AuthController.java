package space.raychi.wellspring.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import space.raychi.wellspring.api.ApiResponses;
import space.raychi.wellspring.dto.CsrfResponse;
import space.raychi.wellspring.dto.ChangePasswordRequest;
import space.raychi.wellspring.dto.LoginRequest;
import space.raychi.wellspring.dto.SessionResponse;
import space.raychi.wellspring.service.AuthService;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    AuthController(AuthService auth) { this.auth = auth; }

    @GetMapping("/csrf")
    ResponseEntity<CsrfResponse> csrf(CsrfToken token) { return ApiResponses.ok(auth.csrf(token)); }

    @GetMapping("/session")
    ResponseEntity<SessionResponse> session(Authentication authentication) {
        return ApiResponses.ok(auth.session(authentication));
    }

    @PostMapping("/login")
    ResponseEntity<SessionResponse> login(@RequestBody LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        return ApiResponses.ok(auth.login(input, request, response));
    }

    @PostMapping("/password")
    ResponseEntity<Void> changePassword(Authentication authentication, @RequestBody ChangePasswordRequest input,
                                       HttpServletRequest request, HttpServletResponse response) {
        auth.changePassword(authentication, input, request, response);
        return ApiResponses.noContent();
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        auth.logout(request, response);
        return ApiResponses.noContent();
    }
}
