package com.hotel.langchain.service;

import com.hotel.langchain.service.HotelBackendClient.HotelBackendException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoomTypeServiceTest {

    private final HotelBackendClient backendClient = mock(HotelBackendClient.class);
    private final RoomTypeService service = new RoomTypeService(backendClient);

    @Test
    void typesComeFromTheBackendOnceAndAreCached() throws Exception {
        backendReturns(List.of(
                Map.of("code", "SINGLE", "name", "Единична стая"),
                Map.of("code", "DOUBLE", "name", "Двойна стая")));

        List<RoomTypeService.RoomType> types = service.getRoomTypes("seven_stars");
        service.getRoomTypes("seven_stars");

        assertThat(types).containsExactly(
                new RoomTypeService.RoomType("SINGLE", "Единична стая"),
                new RoomTypeService.RoomType("DOUBLE", "Двойна стая"));
        verify(backendClient, times(1)).request(eq("seven_stars"), eq("get_room_types"), any());
    }

    @Test
    void entriesWithoutCodeAreSkippedAndMissingNameIsTheCode() throws Exception {
        Map<String, Object> withoutCode = new HashMap<>();
        withoutCode.put("name", "Без код");
        backendReturns(Arrays.asList(Map.of("code", "APARTMENT"), withoutCode, "не е обект"));

        assertThat(service.getRoomTypes("seven_stars"))
                .containsExactly(new RoomTypeService.RoomType("APARTMENT", "APARTMENT"));
    }

    @Test
    void failedBackendGivesEmptyListAndIsNotAskedAgainRightAway() throws Exception {
        when(backendClient.request(eq("seven_stars"), eq("get_room_types"), any())).thenThrow(new TimeoutException());

        assertThat(service.getRoomTypes("seven_stars")).isEmpty();
        assertThat(service.getRoomTypes("seven_stars")).isEmpty();
        // Следващият опит – най-рано след минута, за да не чака всеки чат по 5s
        verify(backendClient, times(1)).request(eq("seven_stars"), eq("get_room_types"), any());
    }

    @Test
    void eachHotelHasItsOwnTypes() throws Exception {
        when(backendClient.request(eq("seven_stars"), eq("get_room_types"), any()))
                .thenReturn(List.of(Map.of("code", "SINGLE", "name", "Единична стая")));
        when(backendClient.request(eq("40_robbers"), eq("get_room_types"), any()))
                .thenThrow(new HotelBackendException("Unknown event"));

        assertThat(service.getRoomTypes("seven_stars")).hasSize(1);
        assertThat(service.getRoomTypes("40_robbers")).isEmpty();
    }

    @Test
    void normalizeGivesTheCodeOnlyForATypeOfTheHotel() throws Exception {
        backendReturns(List.of(Map.of("code", "DOUBLE", "name", "Двойна стая")));

        assertThat(service.normalize("seven_stars", " double ")).isEqualTo("DOUBLE");
        assertThat(service.normalize("seven_stars", "SUITE")).isNull();
        assertThat(service.normalize("seven_stars", "null")).isNull();
        assertThat(service.normalize("seven_stars", " ")).isNull();
        assertThat(service.normalize("seven_stars", null)).isNull();
    }

    @Test
    void withoutKnownTypesNormalizePassesTheCodeToTheBackend() throws Exception {
        when(backendClient.request(eq("seven_stars"), eq("get_room_types"), any())).thenThrow(new TimeoutException());

        // booking-system ще провери кода
        assertThat(service.normalize("seven_stars", "suite")).isEqualTo("SUITE");
    }

    @Test
    void nameAndPromptDescription() throws Exception {
        backendReturns(List.of(
                Map.of("code", "SINGLE", "name", "Единична стая"),
                Map.of("code", "DOUBLE", "name", "Двойна стая")));

        assertThat(service.nameOf("seven_stars", "DOUBLE")).isEqualTo("Двойна стая");
        assertThat(service.nameOf("seven_stars", "SUITE")).isEqualTo("SUITE");
        assertThat(service.describeForPrompt("seven_stars")).isEqualTo("SINGLE (Единична стая), DOUBLE (Двойна стая)");
    }

    @Test
    void promptSaysWhenTypesAreUnknown() throws Exception {
        when(backendClient.request(eq("seven_stars"), eq("get_room_types"), any())).thenThrow(new TimeoutException());

        assertThat(service.describeForPrompt("seven_stars")).isEqualTo("няма информация в момента");
    }

    private void backendReturns(Object data) throws Exception {
        when(backendClient.request(eq("seven_stars"), eq("get_room_types"), any())).thenReturn(data);
    }
}
