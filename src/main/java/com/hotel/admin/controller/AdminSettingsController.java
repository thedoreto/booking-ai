package com.hotel.admin.controller;

import com.hotel.admin.config.AdminAuthInterceptor;
import com.hotel.admin.service.AdminAuthService.Admin;
import com.hotel.langchain.service.HotelLanguages;
import com.hotel.langchain.service.HotelLanguages.Languages;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Настройките на хотела за админ панела: езиците ({ languages: [{ code, name }], defaultLanguage }) – за преводите.
// Езиците се добавят от админа на AI асистента (засега в hotel_settings), не от админа на хотела.
@RestController
@RequestMapping("/api/admin/settings")
public class AdminSettingsController {

    private final HotelLanguages hotelLanguages;

    public AdminSettingsController(HotelLanguages hotelLanguages) {
        this.hotelLanguages = hotelLanguages;
    }

    @GetMapping
    public Languages settings(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin) {
        return hotelLanguages.of(admin.hotelId());
    }
}
