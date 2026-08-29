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

    public List<Shortcut> getShortcutsForHotel(String hotelId) {
        System.out.println("get shortcuts for hotel:" + hotelId);
        if (hotelId == null || hotelId.isBlank()) {
            return List.of();
        }
        return shortcutRepository.findAllByHotelId(hotelId);
    }
}