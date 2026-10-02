package space.raychi.wellspring.config;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import space.raychi.wellspring.service.AdminAccountService;
import space.raychi.wellspring.service.AdminPrincipal;

public class CredentialVersionFilter extends OncePerRequestFilter {
    private final AdminAccountService accounts;
    public CredentialVersionFilter(AdminAccountService accounts) { this.accounts = accounts; }
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AdminPrincipal principal && !accounts.isCurrent(principal)) {
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
        }
        chain.doFilter(request, response);
    }
}
