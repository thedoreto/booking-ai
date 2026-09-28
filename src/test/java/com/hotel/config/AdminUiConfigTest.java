package com.hotel.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

// Само Spring MVC + AdminUiConfig, без Mongo, Kafka и Gemini
@SpringJUnitWebConfig(AdminUiConfigTest.WebConfig.class)
class AdminUiConfigTest {

    @Configuration
    @EnableWebMvc
    @Import(AdminUiConfig.class)
    static class WebConfig {
    }

    @Test
    void adminOpensTheBuiltPage(WebApplicationContext context) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        mvc.perform(get("/admin")).andExpect(redirectedUrl("/admin/"));
        mvc.perform(get("/admin/")).andExpect(forwardedUrl("/admin/index.html"));
    }
}
