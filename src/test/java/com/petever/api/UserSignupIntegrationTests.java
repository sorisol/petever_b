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

    @Test
    void returnsFieldErrorsForInvalidInputWithoutSaving() throws Exception {
        var password = "가".repeat(25);
        var response = postSignup("""
                {
                  "email": "not-an-email",
                  "password": "%s",
                  "passwordConfirmation": "different",
                  "nickname": "x"
                }
                """.formatted(password));

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("INVALID_INPUT"));
        assertTrue(response.body().contains("email"));
        assertTrue(response.body().contains("password"));
        assertTrue(response.body().contains("passwordConfirmation"));
        assertTrue(response.body().contains("nickname"));
        assertEquals(0, jdbc.queryForObject("select count(*) from users", Integer.class));
    }

    @Test
    void returnsConflictForAnExistingNormalizedEmail() throws Exception {
        var first = postSignup("""
                {"email":"member@example.com","password":"valid-password",
                 "passwordConfirmation":"valid-password","nickname":"회원일"}
                """);
        var duplicate = postSignup("""
                {"email":" MEMBER@EXAMPLE.COM ","password":"other-password",
                 "passwordConfirmation":"other-password","nickname":"회원이"}
                """);

        assertEquals(201, first.statusCode());
        assertEquals(409, duplicate.statusCode());
        assertTrue(duplicate.body().contains("DUPLICATE_EMAIL"));
        assertEquals(1, jdbc.queryForObject("select count(*) from users", Integer.class));
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
