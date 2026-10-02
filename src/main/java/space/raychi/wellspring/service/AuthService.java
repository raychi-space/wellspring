package space.raychi.wellspring.service;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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
import org.springframework.stereotype.Service;
import space.raychi.wellspring.api.ApiException;
import space.raychi.wellspring.dto.CsrfResponse;
import space.raychi.wellspring.dto.LoginRequest;
import space.raychi.wellspring.dto.SessionResponse;

@Service
public class AuthService {
    private final AuthenticationManager manager;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrfTokens;

    public AuthService(AuthenticationManager manager, SecurityContextRepository contexts, CsrfTokenRepository csrfTokens) {
        this.manager = manager;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
    }

    public CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getToken(), token.getHeaderName());
    }

    public SessionResponse session(Authentication authentication) {
        boolean valid = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken);
        return new SessionResponse(valid, valid ? authentication.getName() : null);
    }

    public SessionResponse login(LoginRequest input, HttpServletRequest request, HttpServletResponse response) {
        if (input == null || input.username() == null || input.password() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "用户名或密码错误。");
        }
        try {
            Authentication authentication = manager.authenticate(
                    new UsernamePasswordAuthenticationToken(input.username(), input.password()));
            request.getSession(true);
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

    public void logout(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        csrfTokens.saveToken(null, request, response);
    }
}
