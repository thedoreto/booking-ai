package com.hotel.langchain.exception;

import com.hotel.langchain.context.TenantContext;

public class OpenDatePickerException extends RuntimeException {

    public static final String SPECIAL_ACTION_OPEN_DATE_PICKER = "SPECIAL_ACTION:OPEN_DATE_PICKER";
    public static final String OPEN_DATE_PICKER_ACTION = "OPEN_DATE_PICKER";
    public static final String DATE_PICKER_REPLY = "Моля, изберете период за настаняване от календара, за да продължим.";

    public OpenDatePickerException() {
        super(SPECIAL_ACTION_OPEN_DATE_PICKER);
        TenantContext.requestDatePicker();
    }
}
