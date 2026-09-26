package com.hotel.langchain.service;

import com.hotel.langchain.model.Shortcut;
import com.hotel.langchain.repository.ShortcutRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShortcutServiceTest {

    private final ShortcutRepository repository = mock(ShortcutRepository.class);
    private final ShortcutService service = new ShortcutService(repository);

    @Test
    void guestDoesNotGetButtonsHiddenFromGuests() {
        Shortcut noGuestField = button(null);
        Shortcut visible = button(true);
        Shortcut hidden = button(false);
        when(repository.findAllByHotelId("seven_stars")).thenReturn(List.of(noGuestField, visible, hidden));

        assertThat(service.getShortcutsForHotel("seven_stars", null)).containsExactly(noGuestField, visible);
        assertThat(service.getShortcutsForHotel("seven_stars", " ")).containsExactly(noGuestField, visible);
        assertThat(service.getShortcutsForHotel("seven_stars", "user-1")).containsExactly(noGuestField, visible, hidden);
    }

    private static Shortcut button(Boolean guestIsActive) {
        Shortcut shortcut = new Shortcut();
        if (guestIsActive != null) {
            Shortcut.Guest guest = new Shortcut.Guest();
            guest.setIsActive(guestIsActive);
            shortcut.setGuest(guest);
        }
        return shortcut;
    }
}
