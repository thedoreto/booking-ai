package com.hotel.admin.config;

import com.hotel.admin.service.AdminAuthService;
import com.hotel.admin.service.AdminAuthService.Admin;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Optional;

// Пази всички адреси на админ панела освен /login (регистрацията – в AdminWebConfig): без валиден токен – 401.
// Влезлият админ отива в request-а (ADMIN) – контролерите го взимат с @RequestAttribute и хотелът е винаги от токена.
@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    public static final String ADMIN = "admin";

    private final AdminAuthService adminAuthService;

    public AdminAuthInterceptor(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        // CORS preflight няма токен
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        Optional<Admin> admin = adminAuthService.verify(bearer(request.getHeader("Authorization")));
        if (admin.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"error\":\"UNAUTHORIZED\"}");
            return false;
        }
        request.setAttribute(ADMIN, admin.get());
        return true;
    }

    private static String bearer(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim()
                : null;
    }
}
