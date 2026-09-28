package com.hotel.admin.controller;

import com.hotel.admin.service.AdminAuthService;
import com.hotel.admin.service.AdminAuthService.Admin;
import com.hotel.admin.service.AdminAuthService.LoginResult;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Входът в админ панела. За разлика от чата тук има HTTP кодове: 401 – грешни данни или невалиден/изтекъл токен
// (страницата иска нов вход), 429 – твърде много грешни опити. Грешките са кодове; текстовете са в страницата.
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    public record LoginRequest(String hotelId, String email, String password) {}

    public record LoginResponse(String token, String hotelId, String email, String name) {}

    private final AdminAuthService adminAuthService;

    public AdminAuthController(AdminAuthService adminAuthService) {
        this.adminAuthService = adminAuthService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        LoginResult result = adminAuthService.login(request.hotelId(), request.email(), request.password());
        return switch (result.outcome()) {
            case OK -> {
                Admin admin = result.admin();
                yield ResponseEntity.ok(new LoginResponse(result.token(), admin.hotelId(), admin.email(), admin.name()));
            }
            case LOCKED -> error(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS");
            case INVALID -> error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS");
        };
    }

    // Кой е влязъл – страницата проверява с него дали пазеният токен още важи
    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return adminAuthService.verify(bearer(authorization))
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"));
    }

    private static String bearer(String authorization) {
        return authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring("Bearer ".length()).trim()
                : null;
    }

    private static ResponseEntity<?> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }
}
