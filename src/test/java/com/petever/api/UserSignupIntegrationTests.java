package com.petever.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("supabase")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:animal-schema.sql",
    "spring.jpa.hibernate.ddl-auto=validate",
    "LOSSINFO_API_KEY=",
    "ANIMAL_API_SERVICE_KEY="
})
class UserSignupIntegrationTests {
    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearUsers() {
        jdbc.update("delete from users");
    }

    @Test
    void createsAnActiveUserWithNormalizedEmailAndBcrypt12Password() throws Exception {
        var response = postSignup("""
                {
                  "email": "  USER@Example.com  ",
                  "password": "correct horse battery staple",
                  "passwordConfirmation": "correct horse battery staple",
                  "nickname": "  몽글집사  ",
                  "phone": " 010-1234-5678 "
                }
                """);

        assertEquals(201, response.statusCode());
        var row = jdbc.queryForMap("select * from users where email = ?", "user@example.com");
        assertEquals("USER", row.get("system_role"));
        assertEquals("ACTIVE", row.get("status"));
        assertEquals("몽글집사", row.get("nickname"));
        assertEquals("010-1234-5678", row.get("phone"));
        var hash = row.get("password_hash").toString();
        assertTrue(hash.startsWith("$2a$12$"));
        assertTrue(new BCryptPasswordEncoder(12).matches("correct horse battery staple", hash));
        assertFalse(response.body().contains("password"));
    }

    private HttpResponse<String> postSignup(String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/signup"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }
}
