package com.hotel.langchain.service;

import com.hotel.langchain.repository.ShortcutRepository;
import com.hotel.langchain.model.Shortcut;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ShortcutService {

    private final ShortcutRepository shortcutRepository;

    public ShortcutService(ShortcutRepository shortcutRepository) {
        this.shortcutRepository = shortcutRepository;
    }

    // guest – без вход: само бутоните, които гостът вижда (guest.isActive)
    public List<Shortcut> getShortcutsForHotel(String hotelId, boolean guest) {
        System.out.println("get shortcuts for hotel:" + hotelId + ", guest: " + guest);
        if (hotelId == null || hotelId.isBlank()) {
            return List.of();
        }
        List<Shortcut> shortcuts = shortcutRepository.findAllByHotelId(hotelId);
        return guest ? shortcuts.stream().filter(Shortcut::isVisibleToGuest).toList() : shortcuts;
    }

    // null, ако бутонът липсва или е неактивен
    public Shortcut findActiveShortcut(String hotelId, String shortcutId) {
        if (hotelId == null || hotelId.isBlank() || shortcutId == null || shortcutId.isBlank()) {
            return null;
        }
        return shortcutRepository.findActiveByShortcutId(hotelId, shortcutId);
    }
}