package com.hotel.admin.controller;

import com.hotel.admin.controller.AdminAuthController.LoginRequest;
import com.hotel.admin.controller.AdminAuthController.LoginResponse;
import com.hotel.admin.service.AdminAuthService;
import com.hotel.admin.service.AdminAuthService.Admin;
import com.hotel.admin.service.AdminAuthService.LoginResult;
import com.hotel.admin.service.AdminAuthService.Outcome;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminAuthControllerTest {

    private static final Admin ADMIN = new Admin("40_robbers", "the.doreto@gmail.com", "Теодора");

    private final AdminAuthService service = mock(AdminAuthService.class);
    private final AdminAuthController controller = new AdminAuthController(service);

    @Test
    void loginReturnsTokenAndAdmin() {
        when(service.login("40_robbers", "the.doreto@gmail.com", "pass"))
                .thenReturn(new LoginResult(Outcome.OK, "jwt", ADMIN));

        ResponseEntity<?> response = controller.login(new LoginRequest("40_robbers", "the.doreto@gmail.com", "pass"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(new LoginResponse("jwt", "40_robbers", "the.doreto@gmail.com", "Теодора"));
    }

    @Test
    void wrongDataIs401AndLockIs429() {
        when(service.login(any(), any(), any())).thenReturn(new LoginResult(Outcome.INVALID, null, null));
        ResponseEntity<?> invalid = controller.login(new LoginRequest("40_robbers", "x", "y"));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(invalid.getBody()).isEqualTo(Map.of("error", "INVALID_CREDENTIALS"));

        when(service.login(any(), any(), any())).thenReturn(new LoginResult(Outcome.LOCKED, null, null));
        ResponseEntity<?> locked = controller.login(new LoginRequest("40_robbers", "x", "y"));
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(locked.getBody()).isEqualTo(Map.of("error", "TOO_MANY_ATTEMPTS"));
    }

    @Test
    void meTakesTheTokenFromTheBearerHeader() {
        when(service.verify("jwt")).thenReturn(Optional.of(ADMIN));

        ResponseEntity<?> response = controller.me("Bearer jwt");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(ADMIN);
        verify(service).verify("jwt");
    }

    @Test
    void meWithoutValidTokenIs401() {
        when(service.verify(any())).thenReturn(Optional.empty());

        assertThat(controller.me(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.me("Basic abc").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(controller.me("Bearer bad").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
