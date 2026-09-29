package space.raychi.wellspring.auth;

import space.raychi.wellspring.api.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthenticationManager manager;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrfTokens;

    AuthController(AuthenticationManager manager, SecurityContextRepository contexts, CsrfTokenRepository csrfTokens) {
        this.manager = manager;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
    }

    public record LoginRequest(String username, String password) {}
    public record SessionResponse(boolean authenticated, String username) {}

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @GetMapping("/session")
    SessionResponse session(Authentication authentication) {
        boolean valid = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken);
        return new SessionResponse(valid, valid ? authentication.getName() : null);
    }

    @PostMapping("/login")
    SessionResponse login(@RequestBody LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        if (input.username() == null || input.password() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误。");
        }
        try {
            Authentication authentication = manager.authenticate(
                    new UsernamePasswordAuthenticationToken(input.username(), input.password()));
            HttpSession session = request.getSession(true);
            request.changeSessionId();
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            contexts.saveContext(context, request, response);
            csrfTokens.saveToken(null, request, response);
            return new SessionResponse(true, authentication.getName());
        } catch (BadCredentialsException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误。");
        }
    }

    @PostMapping("/logout")
    void logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        csrfTokens.saveToken(null, request, response);
        response.setStatus(HttpServletResponse.SC_NO_CONTENT);
    }
}
