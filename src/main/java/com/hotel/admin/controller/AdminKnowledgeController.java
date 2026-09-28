package com.hotel.admin.controller;

import com.hotel.admin.config.AdminAuthInterceptor;
import com.hotel.admin.service.AdminAuthService.Admin;
import com.hotel.admin.service.AdminKnowledgeService;
import com.hotel.admin.service.AdminKnowledgeService.KnowledgeInUseException;
import com.hotel.admin.service.AdminKnowledgeService.KnowledgeWithUsage;
import com.hotel.admin.service.AdminKnowledgeService.ShortcutRef;
import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.service.KnowledgeService.EmbeddingFailedException;
import com.hotel.knowledge.service.KnowledgeService.InvalidKnowledgeException;
import com.hotel.knowledge.service.KnowledgeService.KnowledgeChanges;
import com.hotel.knowledge.service.KnowledgeTranslator.TranslationFailedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

// Знанията на хотела на влезлия админ (knowledge_<hotelId>): преглед, добавяне, редакция, изтриване.
// usedBy – бутоните, които ползват знанието; такова знание не се трие (409 IN_USE).
// translations – преводите за бутоните (/translations/{език}: предложение от Gemini без запис, запис на един език;
// /translate-all – всички езици с Gemini). Грешки: 400 TEXT_REQUIRED / TEXT_TOO_LONG / UNKNOWN_LANGUAGE / NO_LANGUAGES,
// 404 NOT_FOUND, 409 IN_USE, 503 EMBEDDING_FAILED / TRANSLATION_FAILED (нищо не е записано).
@RestController
@RequestMapping("/api/admin/knowledge")
public class AdminKnowledgeController {

    public record KnowledgeItem(String id, String title, String category, List<String> tags, String source, String text,
                                Map<String, String> translations, List<ShortcutRef> usedBy) {}

    public record TranslationRequest(String text) {}

    public record TranslationSuggestion(String language, String text) {}

    private final AdminKnowledgeService adminKnowledgeService;

    public AdminKnowledgeController(AdminKnowledgeService adminKnowledgeService) {
        this.adminKnowledgeService = adminKnowledgeService;
    }

    @GetMapping
    public List<KnowledgeItem> list(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin) {
        return adminKnowledgeService.list(admin.hotelId()).stream()
                .map(AdminKnowledgeController::toItem)
                .toList();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin,
                                    @RequestBody KnowledgeChanges changes) {
        return handle(() -> ResponseEntity.status(HttpStatus.CREATED)
                .body(toItem(adminKnowledgeService.create(admin.hotelId(), changes))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin, @PathVariable String id,
                                    @RequestBody KnowledgeChanges changes) {
        return handle(() -> adminKnowledgeService.update(admin.hotelId(), id, changes)
                .<ResponseEntity<?>>map(k -> ResponseEntity.ok(toItem(k)))
                .orElseGet(AdminKnowledgeController::notFound));
    }

    @PostMapping("/{id}/translations/{language}/suggest")
    public ResponseEntity<?> suggestTranslation(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin,
                                                @PathVariable String id, @PathVariable String language) {
        return handle(() -> adminKnowledgeService.suggestTranslation(admin.hotelId(), id, language)
                .<ResponseEntity<?>>map(text -> ResponseEntity.ok(new TranslationSuggestion(language, text)))
                .orElseGet(AdminKnowledgeController::notFound));
    }

    @PutMapping("/{id}/translations/{language}")
    public ResponseEntity<?> saveTranslation(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin,
                                             @PathVariable String id, @PathVariable String language,
                                             @RequestBody TranslationRequest request) {
        return handle(() -> adminKnowledgeService.saveTranslation(admin.hotelId(), id, language, request.text())
                .<ResponseEntity<?>>map(k -> ResponseEntity.ok(toItem(k)))
                .orElseGet(AdminKnowledgeController::notFound));
    }

    @PostMapping("/{id}/translate-all")
    public ResponseEntity<?> translateAll(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin, @PathVariable String id) {
        return handle(() -> adminKnowledgeService.translateAll(admin.hotelId(), id)
                .<ResponseEntity<?>>map(k -> ResponseEntity.ok(toItem(k)))
                .orElseGet(AdminKnowledgeController::notFound));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin, @PathVariable String id) {
        try {
            return adminKnowledgeService.delete(admin.hotelId(), id) ? ResponseEntity.noContent().build() : notFound();
        } catch (KnowledgeInUseException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "IN_USE", "usedBy", e.getUsedBy()));
        }
    }

    private static ResponseEntity<?> handle(Supplier<ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (InvalidKnowledgeException e) {
            return error(HttpStatus.BAD_REQUEST, e.getCode());
        } catch (EmbeddingFailedException e) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, "EMBEDDING_FAILED");
        } catch (TranslationFailedException e) {
            System.err.println("Knowledge translation failed: " + e.getMessage());
            return error(HttpStatus.SERVICE_UNAVAILABLE, "TRANSLATION_FAILED");
        }
    }

    private static ResponseEntity<?> notFound() {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    private static ResponseEntity<?> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }

    private static KnowledgeItem toItem(KnowledgeWithUsage k) {
        KnowledgeDocument d = k.document();
        return new KnowledgeItem(d.getId(), d.getTitle(), d.getCategory(), d.getTags(), d.getSource(), d.getText(),
                d.getTranslations() != null ? d.getTranslations() : Map.of(), k.usedBy());
    }
}
