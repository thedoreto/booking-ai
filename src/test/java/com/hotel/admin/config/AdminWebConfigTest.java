package com.hotel.admin.config;

import com.hotel.admin.controller.AdminAuthController;
import com.hotel.admin.controller.AdminKnowledgeController;
import com.hotel.admin.service.AdminAuthService;
import com.hotel.config.CorsConfig;
import com.hotel.admin.service.AdminAuthService.Admin;
import com.hotel.admin.service.AdminAuthService.LoginResult;
import com.hotel.admin.service.AdminAuthService.Outcome;
import com.hotel.knowledge.model.KnowledgeDocument;
import com.hotel.knowledge.service.KnowledgeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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

    @Configuration
    @EnableWebMvc
    @Import({CorsConfig.class, AdminWebConfig.class, AdminAuthInterceptor.class, AdminAuthController.class, AdminKnowledgeController.class})
    static class WebConfig {
        @Bean
        AdminAuthService adminAuthService() {
            return mock(AdminAuthService.class);
        }

        @Bean
        KnowledgeService knowledgeService() {
            return mock(KnowledgeService.class);
        }
    }

    @Autowired
    private AdminAuthService adminAuthService;
    @Autowired
    private KnowledgeService knowledgeService;
    private MockMvc mvc;

    @BeforeEach
    void setUp(WebApplicationContext context) {
        reset(adminAuthService, knowledgeService);
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
        for (String path : List.of("/api/admin/me", "/api/admin/knowledge")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized())
                    .andExpect(content().json("{\"error\":\"UNAUTHORIZED\"}"));
            mvc.perform(get(path).header("Authorization", "Bearer bad")).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "good")).andExpect(status().isUnauthorized());
        }
        verify(knowledgeService, never()).findAll(any());
    }

    @Test
    void meReturnsTheAdminFromTheToken() throws Exception {
        mvc.perform(get("/api/admin/me").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"hotelId\":\"40_robbers\",\"email\":\"the.doreto@gmail.com\",\"name\":\"Теодора\"}"));
    }

    @Test
    void knowledgeOfTheHotelFromTheTokenWithoutEmbedding() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId("66f000000000000000000001");
        doc.setTitle("Паркинг");
        doc.setCategory("Услуги");
        doc.setTags(List.of("паркинг"));
        doc.setText("Безплатен паркинг до хотела.");
        doc.setEmbedding(List.of(0.1, 0.2));
        when(knowledgeService.findAll("40_robbers")).thenReturn(List.of(doc));

        mvc.perform(get("/api/admin/knowledge").param("hotelId", "seven_stars").header("Authorization", "Bearer good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("66f000000000000000000001"))
                .andExpect(jsonPath("$[0].title").value("Паркинг"))
                .andExpect(jsonPath("$[0].text").value("Безплатен паркинг до хотела."))
                .andExpect(jsonPath("$[0].embedding").doesNotExist());
        // hotelId от адреса не се ползва
        verify(knowledgeService, never()).findAll("seven_stars");
    }

    @Test
    void editGoesToTheHotelFromTheToken() throws Exception {
        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setId("66f000000000000000000001");
        doc.setText("Нов текст");
        when(knowledgeService.update(eq("40_robbers"), eq("66f000000000000000000001"), any())).thenReturn(Optional.of(doc));

        mvc.perform(put("/api/admin/knowledge/66f000000000000000000001").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Паркинг\",\"tags\":[\"паркинг\"],\"text\":\"Нов текст\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Нов текст"));
        mvc.perform(put("/api/admin/knowledge/66f000000000000000000001").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"x\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void editErrorsAreCodes() throws Exception {
        when(knowledgeService.update(eq("40_robbers"), eq("missing"), any())).thenReturn(Optional.empty());
        when(knowledgeService.update(eq("40_robbers"), eq("empty"), any()))
                .thenThrow(new KnowledgeService.InvalidKnowledgeException("TEXT_REQUIRED"));
        when(knowledgeService.update(eq("40_robbers"), eq("gemini"), any()))
                .thenThrow(new KnowledgeService.EmbeddingFailedException(new RuntimeException("503")));

        String body = "{\"text\":\"x\"}";
        mvc.perform(put("/api/admin/knowledge/missing").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("NOT_FOUND"));
        mvc.perform(put("/api/admin/knowledge/empty").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("TEXT_REQUIRED"));
        mvc.perform(put("/api/admin/knowledge/gemini").header("Authorization", "Bearer good")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("EMBEDDING_FAILED"));
    }

    @Test
    void corsPreflightPassesWithoutToken() throws Exception {
        mvc.perform(options("/api/admin/knowledge")
                        .header("Origin", "http://localhost:5174")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().is2xxSuccessful());
    }
}
