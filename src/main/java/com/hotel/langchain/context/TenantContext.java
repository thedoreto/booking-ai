package com.hotel.langchain.context;

public class TenantContext {
    private static final ThreadLocal<String> CURRENT_HOTEL_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();
    // Действие за UI, поискано от tool по време на заявката (календар, избор на стаи...)
    private static final ThreadLocal<UiAction> UI_ACTION = new ThreadLocal<>();

    public record UiAction(String actionType, String reply, Object data) {}

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

    public static void requestUiAction(UiAction action) {
        UI_ACTION.set(action);
    }

    public static UiAction getUiAction() {
        return UI_ACTION.get();
    }

    public static void clear() {
        UI_ACTION.remove();
        CURRENT_HOTEL_ID.remove();
        CURRENT_USER_ID.remove();
    }
}