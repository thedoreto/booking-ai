package com.hotel.langchain.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

@Component
public class KafkaCertInitializer {

    @PostConstruct
    public void init() {
        try {
            Path dir = Paths.get("/tmp/certs");
            Files.createDirectories(dir);

            copyResource("certs/ca.pem", dir.resolve("ca.pem"));
            copyResource("certs/aiven-keystore.p12", dir.resolve("aiven-keystore.p12"));
            copyResource("certs/aiven-truststore.p12", dir.resolve("aiven-truststore.p12"));

        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Kafka SSL certificates", e);
        }
    }

    private void copyResource(String resourcePath, Path targetPath) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in != null) {
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            } else {
                throw new FileNotFoundException("Resource not found in classpath: " + resourcePath);
            }
        }
    }
}