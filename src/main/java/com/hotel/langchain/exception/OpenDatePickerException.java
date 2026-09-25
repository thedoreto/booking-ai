package com.hotel.langchain.exception;

import com.hotel.langchain.context.TenantContext;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

public class OpenDatePickerException extends RuntimeException {

    public static final String SPECIAL_ACTION_OPEN_DATE_PICKER = "SPECIAL_ACTION:OPEN_DATE_PICKER";
    public static final String OPEN_DATE_PICKER_ACTION = "OPEN_DATE_PICKER";
    public static final String DATE_PICKER_REPLY = "Моля, изберете период за настаняване от календара, за да продължим.";

    public OpenDatePickerException() {
        this(null, null, null);
    }

    // startDate/endDate/roomType (може null) – с тях UI попълва календара предварително
    public OpenDatePickerException(LocalDate startDate, LocalDate endDate, String roomType) {
        super(SPECIAL_ACTION_OPEN_DATE_PICKER);
        Map<String, Object> prefill = new HashMap<>();
        if (startDate != null) {
            prefill.put("startDate", startDate.toString());
        }
        if (endDate != null) {
            prefill.put("endDate", endDate.toString());
        }
        if (roomType != null) {
            prefill.put("roomType", roomType);
        }
        TenantContext.requestUiAction(
                new TenantContext.UiAction(OPEN_DATE_PICKER_ACTION, DATE_PICKER_REPLY, prefill));
    }
}
