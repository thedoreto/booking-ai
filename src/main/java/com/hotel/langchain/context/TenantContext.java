package com.hotel.langchain.context;

public class TenantContext {
    private static final ThreadLocal<String> CURRENT_HOTEL_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> OPEN_DATE_PICKER = new ThreadLocal<>();

    public static void setHotelId(String hotelId) {
        CURRENT_HOTEL_ID.set(hotelId);
    }

    public static String getHotelId() {
        return CURRENT_HOTEL_ID.get() != null ? CURRENT_HOTEL_ID.get() : "knowledge_seven_stars"; // Дефолтна стойност
    }

    public static void setUserId(String userId) {
        CURRENT_USER_ID.set(userId);
    }

    public static String getUserId() {
        return CURRENT_USER_ID.get() != null ? CURRENT_USER_ID.get() : null;
    }

    public static void requestDatePicker() {
        OPEN_DATE_PICKER.set(true);
    }

    public static boolean isDatePickerRequested() {
        return Boolean.TRUE.equals(OPEN_DATE_PICKER.get());
    }

    public static void clear() {
        OPEN_DATE_PICKER.remove();
        CURRENT_HOTEL_ID.remove();
        CURRENT_USER_ID.remove();
    }
}