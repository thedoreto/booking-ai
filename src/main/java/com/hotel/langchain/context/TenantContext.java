package com.hotel.langchain.context;

public class TenantContext {
    private static final ThreadLocal<String> CURRENT_HOTEL_ID = new ThreadLocal<>();

    public static void setHotelId(String hotelId) {
        CURRENT_HOTEL_ID.set(hotelId);
    }

    public static String getHotelId() {
        return CURRENT_HOTEL_ID.get() != null ? CURRENT_HOTEL_ID.get() : "knowledge_seven_stars"; // Дефолтна стойност
    }

    public static void clear() {
        CURRENT_HOTEL_ID.remove();
    }
}