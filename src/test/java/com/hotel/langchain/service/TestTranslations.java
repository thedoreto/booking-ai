package com.hotel.langchain.service;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// TranslationService за тестовете: forRequest дава Texts, който вместо текста връща ключа, а с параметри –
// „ключ {име=стойност}“; translate – „[език] текст“.
// Така тестът проверява кое съобщение е избрано, без да зависи от записите в Mongo.
public final class TestTranslations {

    private TestTranslations() {
    }

    public static TranslationService keys() {
        TranslationService translations = mock(TranslationService.class);
        when(translations.forRequest(any(), any())).thenAnswer(call -> new KeyTexts(call.getArgument(1)));
        return translations;
    }

    private record KeyTexts(String language) implements Texts {

        @Override
        public String message(String key, Map<String, ?> params) {
            return params.isEmpty() ? key : key + " " + params;
        }

        @Override
        public String translate(String text) {
            return text == null ? null : "[" + language + "] " + text;
        }

        @Override
        public Map<String, String> withPrefix(String prefix) {
            return Map.of();
        }
    }
}
