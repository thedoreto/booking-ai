package com.hotel.admin.controller;

import com.hotel.admin.config.AdminAuthInterceptor;
import com.hotel.admin.service.AdminAuthService.Admin;
import com.hotel.admin.service.AdminReportService;
import com.hotel.admin.service.AdminReportService.InvalidPeriodException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Отчетите на хотела на влезлия админ: GET /api/admin/reports?days=30 →
// { days, summary, funnel, noRooms, buttons } (виж ChatReports). days извън 1..180 – 400 INVALID_PERIOD.
@RestController
@RequestMapping("/api/admin/reports")
public class AdminReportController {

    private final AdminReportService adminReportService;

    public AdminReportController(AdminReportService adminReportService) {
        this.adminReportService = adminReportService;
    }

    @GetMapping
    public ResponseEntity<?> report(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin,
                                    @RequestParam(defaultValue = "30") int days) {
        try {
            return ResponseEntity.ok(adminReportService.report(admin.hotelId(), days));
        } catch (InvalidPeriodException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "INVALID_PERIOD"));
        }
    }
}
