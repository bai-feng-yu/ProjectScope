package agent_backend.security;

import java.io.IOException;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.security.core.context.SecurityContextHolder;

final class AccountAccessFilter extends OncePerRequestFilter {
    private final ToolAccess access;
    AccountAccessFilter(ToolAccess access) { this.access = access; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication()
                instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken) {
            try { access.requireUser(TaskIdentity.current().userId()); }
            catch (org.springframework.security.access.AccessDeniedException denied) {
                response.sendError(403, "Account disabled");
                return;
            } catch (org.springframework.dao.DataAccessException unavailable) {
                response.sendError(503, "Account policy unavailable");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
