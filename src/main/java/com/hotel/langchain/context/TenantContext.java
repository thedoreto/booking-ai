package com.hotel.langchain.context;

import com.hotel.langchain.log.ChatLogEntry;

public class TenantContext {
    private static final ThreadLocal<String> CURRENT_HOTEL_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();
    // Действие за UI, поискано от tool по време на заявката (календар, избор на стаи...)
    private static final ThreadLocal<UiAction> UI_ACTION = new ThreadLocal<>();
    // Грешка в tool, който връща само текст на модела (без UiAction) – за логовете (ChatLogEntry.errorType)
    private static final ThreadLocal<String> TOOL_ERROR = new ThreadLocal<>();

    // outcome/errorType – за логовете (ChatLogEntry): ok, no_result, rejected, error
    public record UiAction(String actionType, String reply, Object data, String outcome, String errorType) {
        public UiAction(String actionType, String reply, Object data) {
            this(actionType, reply, data, ChatLogEntry.OK, null);
        }
    }

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

    public static void reportToolError(String errorType) {
        TOOL_ERROR.set(errorType);
    }

    public static String getToolError() {
        return TOOL_ERROR.get();
    }

    public static void clear() {
        UI_ACTION.remove();
        TOOL_ERROR.remove();
        CURRENT_HOTEL_ID.remove();
        CURRENT_USER_ID.remove();
    }
}