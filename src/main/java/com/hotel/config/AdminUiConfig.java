package com.hotel.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// Страницата на админ панела (admin-ui, билдната в static/admin): /admin и /admin/ отварят index.html.
// Файловете ѝ (/admin/assets/...) Spring ги сервира сам от static.
@Configuration
public class AdminUiConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/admin", "/admin/");
        registry.addViewController("/admin/").setViewName("forward:/admin/index.html");
    }
}
