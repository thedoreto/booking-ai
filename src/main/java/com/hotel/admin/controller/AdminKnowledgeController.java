package com.hotel.admin.controller;

import com.hotel.admin.config.AdminAuthInterceptor;
import com.hotel.admin.service.AdminAuthService.Admin;
import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.service.KnowledgeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// Знанията на хотела на влезлия админ (knowledge_<hotelId>) – засега само за преглед
@RestController
@RequestMapping("/api/admin/knowledge")
public class AdminKnowledgeController {

    public record KnowledgeItem(String id, String title, String category, List<String> tags, String source, String text) {}

    private final KnowledgeService knowledgeService;

    public AdminKnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public List<KnowledgeItem> list(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin) {
        return knowledgeService.findAll(admin.hotelId()).stream()
                .map(AdminKnowledgeController::toItem)
                .toList();
    }

    private static KnowledgeItem toItem(KnowledgeDocument d) {
        return new KnowledgeItem(d.getId(), d.getTitle(), d.getCategory(), d.getTags(), d.getSource(), d.getText());
    }
}
