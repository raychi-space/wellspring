package space.raychi.wellspring.search;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SearchCacheHeaders extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException {
        if(request.getRequestURI().equals("/api/v1/public/search"))response.setHeader("Cache-Control","no-store");
        chain.doFilter(request,response);
    }
}
