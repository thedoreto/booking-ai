package com.hotel.langchain.service;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// TranslationService за тестовете: вместо текста връща ключа, а с параметри – „ключ {име=стойност}“;
// translate – „[език] текст“.
// Така тестът проверява кое съобщение е избрано, без да зависи от записите в Mongo.
public final class TestTranslations {

    private TestTranslations() {
    }

    public static TranslationService keys() {
        TranslationService translations = mock(TranslationService.class);
        when(translations.message(anyString(), any())).thenAnswer(call -> call.getArgument(0));
        when(translations.message(anyString(), any(), any())).thenAnswer(call -> {
            Map<?, ?> params = call.getArgument(2);
            String key = call.getArgument(0);
            return params.isEmpty() ? key : key + " " + params;
        });
        when(translations.translate(any(), any())).thenAnswer(call ->
                call.getArgument(0) == null ? null : "[" + call.getArgument(1) + "] " + call.getArgument(0));
        return translations;
    }
}
