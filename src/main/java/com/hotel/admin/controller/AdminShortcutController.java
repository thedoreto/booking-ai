package com.hotel.admin.controller;

import com.hotel.admin.config.AdminAuthInterceptor;
import com.hotel.admin.service.AdminAuthService.Admin;
import com.hotel.admin.service.AdminShortcutService;
import com.hotel.admin.service.AdminShortcutService.InvalidShortcutException;
import com.hotel.admin.service.AdminShortcutService.ShortcutChanges;
import com.hotel.admin.service.AdminShortcutService.ShortcutIdTakenException;
import com.hotel.langchain.model.Shortcut;
import com.hotel.langchain.tools.ShortcutToolRunner.ToolInfo;
import org.bson.types.ObjectId;
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
import java.util.Objects;
import java.util.function.Supplier;

// Бутоните на хотела на влезлия админ (shortcuts_<hotelId>): преглед, добавяне, редакция, изтриване и ред в чата.
// /tools – tool-овете, които бутон може да пусне. Грешки: 400 SHORTCUT_ID_REQUIRED / SHORTCUT_ID_INVALID /
// LABEL_REQUIRED / UNKNOWN_LANGUAGE / ACTION_REQUIRED / KNOWLEDGE_REQUIRED / UNKNOWN_KNOWLEDGE / UNKNOWN_TOOL /
// INVALID_ORDER, 404 NOT_FOUND, 409 SHORTCUT_ID_TAKEN (нищо не е записано).
@RestController
@RequestMapping("/api/admin/shortcuts")
public class AdminShortcutController {

    public record ShortcutItem(String shortcutId, Map<String, String> label, String category, boolean isActive,
                               boolean guestVisible, ActionItem action, Integer order) {}

    public record ActionItem(String type, String tool, List<String> knowledgeIds) {}

    public record OrderRequest(List<String> shortcutIds) {}

    private final AdminShortcutService adminShortcutService;

    public AdminShortcutController(AdminShortcutService adminShortcutService) {
        this.adminShortcutService = adminShortcutService;
    }

    @GetMapping
    public List<ShortcutItem> list(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin) {
        return toItems(adminShortcutService.list(admin.hotelId()));
    }

    @GetMapping("/tools")
    public List<ToolInfo> tools() {
        return adminShortcutService.tools();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin,
                                    @RequestBody ShortcutChanges changes) {
        return handle(() -> ResponseEntity.status(HttpStatus.CREATED)
                .body(toItem(adminShortcutService.create(admin.hotelId(), changes))));
    }

    @PutMapping("/order")
    public ResponseEntity<?> reorder(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin,
                                     @RequestBody OrderRequest request) {
        return handle(() -> ResponseEntity.ok(toItems(adminShortcutService.reorder(admin.hotelId(), request.shortcutIds()))));
    }

    @PutMapping("/{shortcutId}")
    public ResponseEntity<?> update(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin,
                                    @PathVariable String shortcutId, @RequestBody ShortcutChanges changes) {
        return handle(() -> adminShortcutService.update(admin.hotelId(), shortcutId, changes)
                .<ResponseEntity<?>>map(s -> ResponseEntity.ok(toItem(s)))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "NOT_FOUND")));
    }

    @DeleteMapping("/{shortcutId}")
    public ResponseEntity<?> delete(@RequestAttribute(AdminAuthInterceptor.ADMIN) Admin admin,
                                    @PathVariable String shortcutId) {
        return adminShortcutService.delete(admin.hotelId(), shortcutId)
                ? ResponseEntity.noContent().build() : error(HttpStatus.NOT_FOUND, "NOT_FOUND");
    }

    private static ResponseEntity<?> handle(Supplier<ResponseEntity<?>> action) {
        try {
            return action.get();
        } catch (InvalidShortcutException e) {
            return error(HttpStatus.BAD_REQUEST, e.getCode());
        } catch (ShortcutIdTakenException e) {
            return error(HttpStatus.CONFLICT, "SHORTCUT_ID_TAKEN");
        }
    }

    private static ResponseEntity<?> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }

    private static List<ShortcutItem> toItems(List<Shortcut> shortcuts) {
        return shortcuts.stream().map(AdminShortcutController::toItem).toList();
    }

    private static ShortcutItem toItem(Shortcut s) {
        Shortcut.Action a = s.getAction();
        ActionItem action = a == null ? null : new ActionItem(a.getType(), a.getTool(),
                a.getKnowledgeIds() == null ? List.of() : a.getKnowledgeIds().stream().filter(Objects::nonNull).map(ObjectId::toHexString).toList());
        return new ShortcutItem(s.getShortcutId(), s.getLabel() != null ? s.getLabel() : Map.of(), s.getCategory(),
                s.isActive(), s.isVisibleToGuest(), action, s.getOrder());
    }
}
