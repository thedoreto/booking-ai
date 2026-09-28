package com.hotel.admin.config;

import com.hotel.admin.controller.AdminAuthController;
import com.hotel.admin.controller.AdminKnowledgeController;
import com.hotel.admin.controller.AdminSettingsController;
import com.hotel.admin.service.AdminAuthService;
import com.hotel.admin.service.AdminAuthService.Admin;
import com.hotel.admin.service.AdminAuthService.LoginResult;
import com.hotel.admin.service.AdminAuthService.Outcome;
import com.hotel.admin.service.AdminKnowledgeService;
import com.hotel.admin.service.AdminKnowledgeService.KnowledgeInUseException;
import com.hotel.admin.service.AdminKnowledgeService.KnowledgeWithUsage;
import com.hotel.admin.service.AdminKnowledgeService.ShortcutRef;
import com.hotel.config.CorsConfig;
import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.service.KnowledgeService.EmbeddingFailedException;
import com.hotel.knowledge.service.KnowledgeService.InvalidKnowledgeException;
import com.hotel.knowledge.service.KnowledgeTranslator.TranslationFailedException;
import com.hotel.langchain.service.HotelLanguages;
import com.hotel.langchain.service.HotelLanguages.Language;
import com.hotel.langchain.service.HotelLanguages.Languages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Адресите на админ панела през Spring MVC (interceptor + контролери), без Mongo, Kafka и Gemini
@SpringJUnitWebConfig(AdminWebConfigTest.WebConfig.class)
class AdminWebConfigTest {

    private static final Admin ADMIN = new Admin("40_robbers", "the.doreto@gmail.com", "Теодора");
    private static final String ID = "66f000000000000000000001";
    private static final String BODY = "{\"title\":\"Паркинг\",\"tags\":[\"паркинг\"],\"text\":\"Нов текст\"}";

    @Configuration
    @EnableWebMvc
    @Import({CorsConfig.class, AdminWebConfig.class, AdminAuthInterceptor.class, AdminAuthController.class,
            AdminKnowledgeController.class, AdminSettingsController.class})
    static class WebConfig {
        @Bean
        AdminAuthService adminAuthService() {
            return mock(AdminAuthService.class);
        }

        @Bean
        AdminKnowledgeService adminKnowledgeService() {
            return mock(AdminKnowledgeService.class);
        }

        @Bean
        HotelLanguages hotelLanguages() {
            return mock(HotelLanguages.class);
        }
    }

    @Autowired
    private AdminAuthService adminAuthService;
    @Autowired
    private AdminKnowledgeService knowledge;
    @Autowired
    private HotelLanguages hotelLanguages;
    private MockMvc mvc;

    @BeforeEach
    void setUp(WebApplicationContext context) {
        reset(adminAuthService, knowledge, hotelLanguages);
        when(adminAuthService.verify(any())).thenReturn(Optional.empty());
        when(adminAuthService.verify("good")).thenReturn(Optional.of(ADMIN));
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void loginIsOpen() throws Exception {
        when(adminAuthService.login("40_robbers", "the.doreto@gmail.com", "123456"))
                .thenReturn(new LoginResult(Outcome.OK, "good", ADMIN));

        mvc.perform(post("/api/admin/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hotelId\":\"40_robbers\",\"email\":\"the.doreto@gmail.com\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("good"));
    }

    @Test
    void everythingElseNeedsAValidToken() throws Exception {
        List<MockHttpServletRequestBuilder> requests = List.of(get("/api/admin/me"), get("/api/admin/knowledge"),
                json(post("/api/admin/knowledge")), json(put("/api/admin/knowledge/" + ID)), delete("/api/admin/knowledge/" + ID));
        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(content().json("{\"error\":\"UNAUTHORIZED\"}"));
        }
        for (String header : List.of("Bearer bad", "good")) {
            mvc.perform(get("/api/admin/knowledge").header("Authorization", header)).andExpect(status().isUnauthorized());
        }
        verify(knowledge, never()).list(anyString());
        verify(knowledge, never()).create(anyString(), any());
        verify(knowledge, never()).delete(anyString(), anyString());
    }

    @Test
    void meReturnsTheAdminFromTheToken() throws Exception {
        mvc.perform(authorized(get("/api/admin/me")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"hotelId\":\"40_robbers\",\"email\":\"the.doreto@gmail.com\",\"name\":\"Теодора\"}"));
    }

    @Test
    void knowledgeOfTheHotelFromTheTokenWithButtonsAndWithoutEmbedding() throws Exception {
        KnowledgeDocument doc = document();
        doc.setEmbedding(List.of(0.1, 0.2));
        when(knowledge.list("40_robbers")).thenReturn(List.of(
                new KnowledgeWithUsage(doc, List.of(new ShortcutRef("parking", "Паркинг")))));

        mvc.perform(authorized(get("/api/admin/knowledge").param("hotelId", "seven_stars")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ID))
                .andExpect(jsonPath("$[0].title").value("Паркинг"))
                .andExpect(jsonPath("$[0].usedBy[0].shortcutId").value("parking"))
                .andExpect(jsonPath("$[0].usedBy[0].label").value("Паркинг"))
                .andExpect(jsonPath("$[0].embedding").doesNotExist());
        // hotelId от адреса не се ползва
        verify(knowledge, never()).list("seven_stars");
    }

    @Test
    void createReturns201() throws Exception {
        when(knowledge.create(eq("40_robbers"), any())).thenReturn(new KnowledgeWithUsage(document(), List.of()));

        mvc.perform(authorized(json(post("/api/admin/knowledge"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(ID))
                .andExpect(jsonPath("$.usedBy").isEmpty());
    }

    @Test
    void editGoesToTheHotelFromTheToken() throws Exception {
        when(knowledge.update(eq("40_robbers"), eq(ID), any())).thenReturn(Optional.of(new KnowledgeWithUsage(document(), List.of())));

        mvc.perform(authorized(json(put("/api/admin/knowledge/" + ID))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Нов текст"));
    }

    @Test
    void createAndEditErrorsAreCodes() throws Exception {
        when(knowledge.update(eq("40_robbers"), eq("missing"), any())).thenReturn(Optional.empty());
        when(knowledge.update(eq("40_robbers"), eq("empty"), any())).thenThrow(new InvalidKnowledgeException("TEXT_REQUIRED"));
        when(knowledge.create(eq("40_robbers"), any())).thenThrow(new EmbeddingFailedException(new RuntimeException("503")));

        mvc.perform(authorized(json(put("/api/admin/knowledge/missing"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mvc.perform(authorized(json(put("/api/admin/knowledge/empty"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("TEXT_REQUIRED"));
        mvc.perform(authorized(json(post("/api/admin/knowledge"))))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("EMBEDDING_FAILED"));
    }

    @Test
    void deleteIs204OrNotFound() throws Exception {
        when(knowledge.delete("40_robbers", ID)).thenReturn(true);
        when(knowledge.delete("40_robbers", "missing")).thenReturn(false);

        mvc.perform(authorized(delete("/api/admin/knowledge/" + ID))).andExpect(status().isNoContent());
        mvc.perform(authorized(delete("/api/admin/knowledge/missing")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void knowledgeUsedByAButtonIsNotDeleted() throws Exception {
        when(knowledge.delete("40_robbers", ID))
                .thenThrow(new KnowledgeInUseException(List.of(new ShortcutRef("parking", "Паркинг"))));

        mvc.perform(authorized(delete("/api/admin/knowledge/" + ID)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IN_USE"))
                .andExpect(jsonPath("$.usedBy[0].label").value("Паркинг"));
    }

    @Test
    void settingsAreTheLanguagesOfTheHotelFromTheToken() throws Exception {
        when(hotelLanguages.of("40_robbers")).thenReturn(new Languages(
                List.of(new Language("bg", "Български"), new Language("en", "English")), "bg"));

        mvc.perform(authorized(get("/api/admin/settings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultLanguage").value("bg"))
                .andExpect(jsonPath("$.languages[1].code").value("en"));
        mvc.perform(get("/api/admin/settings")).andExpect(status().isUnauthorized());
    }

    @Test
    void translationEndpoints() throws Exception {
        KnowledgeDocument translated = document();
        translated.setTranslations(Map.of("en", "Parking is free."));
        when(knowledge.suggestTranslation("40_robbers", ID, "en")).thenReturn(Optional.of("Parking is free."));
        when(knowledge.saveTranslation("40_robbers", ID, "en", "Parking is free."))
                .thenReturn(Optional.of(new KnowledgeWithUsage(translated, List.of())));
        when(knowledge.translateAll("40_robbers", ID)).thenReturn(Optional.of(new KnowledgeWithUsage(translated, List.of())));

        mvc.perform(authorized(post("/api/admin/knowledge/" + ID + "/translations/en/suggest")))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"language\":\"en\",\"text\":\"Parking is free.\"}"));
        mvc.perform(authorized(put("/api/admin/knowledge/" + ID + "/translations/en"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Parking is free.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translations.en").value("Parking is free."));
        mvc.perform(authorized(post("/api/admin/knowledge/" + ID + "/translate-all")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.translations.en").value("Parking is free."));
    }

    @Test
    void translationErrorsAreCodes() throws Exception {
        when(knowledge.suggestTranslation("40_robbers", ID, "fr")).thenThrow(new InvalidKnowledgeException("UNKNOWN_LANGUAGE"));
        when(knowledge.translateAll("40_robbers", ID)).thenThrow(new TranslationFailedException("503", null));

        mvc.perform(authorized(post("/api/admin/knowledge/" + ID + "/translations/fr/suggest")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("UNKNOWN_LANGUAGE"));
        mvc.perform(authorized(post("/api/admin/knowledge/" + ID + "/translate-all")))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("TRANSLATION_FAILED"));
    }

    @Test
    void corsPreflightPassesWithoutToken() throws Exception {
        mvc.perform(options("/api/admin/knowledge")
                        .header("Origin", "http://localhost:5174")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().is2xxSuccessful());
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer good");
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request) {
        return request.contentType(MediaType.APPLICATION_JSON).content(BODY);
    }

    private static KnowledgeDocument document() {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId(ID);
        doc.setTitle("Паркинг");
        doc.setCategory("Услуги");
        doc.setTags(List.of("паркинг"));
        doc.setText("Нов текст");
        return doc;
    }
}
