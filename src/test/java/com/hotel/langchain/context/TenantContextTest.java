package com.hotel.langchain.context;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Без дефолтен хотел: липсващ hotelId е грешка, а не тихо търсене в чужд хотел
class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void returnsTheHotelSetByTheController() {
        TenantContext.setHotelId("seven_stars");

        assertThat(TenantContext.getHotelId()).isEqualTo("seven_stars");
    }

    @Test
    void missingHotelIsAnError() {
        assertThatThrownBy(TenantContext::getHotelId).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void hotelIsGoneAfterClear() {
        TenantContext.setHotelId("seven_stars");
        TenantContext.clear();

        assertThatThrownBy(TenantContext::getHotelId).isInstanceOf(IllegalStateException.class);
    }
}
