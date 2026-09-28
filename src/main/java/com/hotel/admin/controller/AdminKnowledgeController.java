package com.hotel.admin.controller;

import com.hotel.admin.config.AdminAuthInterceptor;
import com.hotel.admin.service.AdminAuthService.Admin;
import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.service.KnowledgeService;
import com.hotel.knowledge.service.KnowledgeService.EmbeddingFailedException;
import com.hotel.knowledge.service.KnowledgeService.InvalidKnowledgeException;
import com.hotel.knowledge.service.KnowledgeService.KnowledgeChanges;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// Знанията на хотела на влезлия админ (knowledge_<hotelId>): преглед и редакция.
// Грешки при редакция: 400 TEXT_REQUIRED / TEXT_TOO_LONG, 404 NOT_FOUND, 503 EMBEDDING_FAILED (нищо не е записано).
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

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin, @PathVariable String id,
                                    @RequestBody KnowledgeChanges changes) {
        try {
            return knowledgeService.update(admin.hotelId(), id, changes)
                    .<ResponseEntity<?>>map(d -> ResponseEntity.ok(toItem(d)))
                    .orElseGet(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND"));
        } catch (InvalidKnowledgeException e) {
            return error(HttpStatus.BAD_REQUEST, e.getCode());
        } catch (EmbeddingFailedException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "EMBEDDING_FAILED");
        }
    }

    private static ResponseEntity<?> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }

    private static KnowledgeItem toItem(KnowledgeDocument d) {
        return new KnowledgeItem(d.getId(), d.getTitle(), d.getCategory(), d.getTags(), d.getSource(), d.getText());
    }
}
