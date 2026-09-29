package com.hotel.admin.controller;

import com.hotel.admin.config.AdminAuthInterceptor;
import com.hotel.admin.service.AdminAuthService.Admin;
import com.hotel.admin.service.AdminReportService.InvalidPeriodException;
import com.hotel.admin.service.AdminSuggestionService;
import com.hotel.admin.service.AdminSuggestionService.AnalysisRunningException;
import com.hotel.admin.service.AdminSuggestionService.InvalidStatusException;
import com.hotel.admin.service.AdminSuggestionService.NoQuestionsException;
import com.hotel.admin.service.AdminSuggestionService.TooSoonException;
import com.hotel.admin.service.SuggestionAnalyzer.AnalysisFailedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Таб „Предложения“ на влезлия админ:
//   GET  /api/admin/suggestions                               – последният анализ (SuggestionAnalysis); няма – 204
//   POST /api/admin/suggestions/analyze {days}                – нов анализ с Gemini (едно извикване); 400 INVALID_PERIOD,
//        400 NO_QUESTIONS, 429 TOO_SOON {retryAt}, 409 ANALYSIS_RUNNING, 503 ANALYSIS_FAILED (нищо не е записано)
//   PUT  /api/admin/suggestions/{analysisId}/items/{itemId} {status: accepted | dismissed} – 204; 400 INVALID_STATUS, 404 NOT_FOUND
@RestController
@RequestMapping("/api/admin/suggestions")
public class AdminSuggestionController {

    public record AnalyzeRequest(Integer days) {}

    public record StatusRequest(String status) {}

    private final AdminSuggestionService suggestionService;

    public AdminSuggestionController(AdminSuggestionService suggestionService) {
        this.suggestionService = suggestionService;
    }

    @GetMapping
    public ResponseEntity<?> latest(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin) {
        return suggestionService.latest(admin.hotelId())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin,
                                     @RequestBody(required = false) AnalyzeRequest request) {
        int days = request == null || request.days() == null ? 30 : request.days();
        try {
            return ResponseEntity.ok(suggestionService.analyze(admin.hotelId(), days));
        } catch (InvalidPeriodException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_PERIOD");
        } catch (NoQuestionsException e) {
            return error(HttpStatus.BAD_REQUEST, "NO_QUESTIONS");
        } catch (TooSoonException e) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("error", "TOO_SOON", "retryAt", e.getRetryAt().toString()));
        } catch (AnalysisRunningException e) {
            return error(HttpStatus.CONFLICT, "ANALYSIS_RUNNING");
        } catch (AnalysisFailedException e) {
            // Причината (грешката от Gemini) е в cause – без нея в лога остава само „Gemini error“
            System.err.println("Suggestions analysis failed for hotelId=" + admin.hotelId() + ": " + e.getMessage()
                    + (e.getCause() != null ? " – " + e.getCause() : ""));
            return error(HttpStatus.SERVICE_UNAVAILABLE, "ANALYSIS_FAILED");
        }
    }

    @PutMapping("/{analysisId}/items/{itemId}")
    public ResponseEntity<?> setStatus(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin,
                                       @PathVariable String analysisId, @PathVariable String itemId,
                                       @RequestBody StatusRequest request) {
        try {
            return suggestionService.setStatus(admin.hotelId(), analysisId, itemId, request.status())
                    ? ResponseEntity.noContent().build() : error(HttpStatus.NOT_FOUND, "NOT_FOUND");
        } catch (InvalidStatusException e) {
            return error(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }
    }

    private static ResponseEntity<?> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }
}
