package com.petever.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import com.petever.api.controller.AuthController;
import com.petever.api.controller.SignupController;
import com.petever.api.entity.User;
import com.petever.api.service.LoginService;
import com.petever.api.service.UserSignupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AuthHttpTests.AuthBeans.class)
class AuthHttpTests {
    @Value("${local.server.port}") int port;
    @Autowired LoginService loginService;
    @Autowired UserSignupService signupService;

    @Test
    void csrfLoginSessionLogoutWorkAcrossRealHttpRequests() throws Exception {
        var user = new User();
        user.id = 42L;
        user.email = "member@example.com";
        user.nickname = "회원";
        when(loginService.authenticate("member@example.com", "valid-password")).thenReturn(user);

        var jar = new CookieManager();
        try (var client = HttpClient.newBuilder().cookieHandler(jar).build()) {
            var csrf = client.send(get("/api/auth/csrf"), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, csrf.statusCode());
            assertTrue(csrf.headers().firstValue("set-cookie").orElse("").contains("HttpOnly"));
            assertTrue(csrf.headers().firstValue("set-cookie").orElse("").contains("SameSite=Lax"));
            var firstId = sessionId(jar);
            var token = csrfToken(csrf.body());
            var login = client.send(post("/api/auth/login", token,
                    "{\"email\":\"member@example.com\",\"password\":\"valid-password\"}"),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            assertFalse(login.body().contains("password"));
            assertNotEquals(firstId, sessionId(jar));
            assertEquals(200, client.send(get("/api/auth/session"), HttpResponse.BodyHandlers.ofString()).statusCode());

            var staleLogout = client.send(post("/api/auth/logout", token, ""),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(403, staleLogout.statusCode());
            var freshCsrf = client.send(get("/api/auth/csrf"), HttpResponse.BodyHandlers.ofString());
            var logout = client.send(post("/api/auth/logout", csrfToken(freshCsrf.body()), ""),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(204, logout.statusCode());
            assertEquals(401, client.send(get("/api/auth/session"), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    @Test
    void signupUsesTheSameRealCsrfCookieFlow() throws Exception {
        var user = new User();
        user.id = 7L;
        user.email = "member@example.com";
        user.nickname = "회원";
        when(signupService.signup(any(), any(), any(), any(), any())).thenReturn(user);

        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var csrf = client.send(get("/api/auth/csrf"), HttpResponse.BodyHandlers.ofString());
            var signup = client.send(post("/api/auth/signup", csrfToken(csrf.body()),
                    "{\"email\":\"member@example.com\",\"password\":\"valid-password\",\"passwordConfirmation\":\"valid-password\",\"nickname\":\"회원\"}"),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(201, signup.statusCode());
            assertTrue(signup.body().contains("member@example.com"));
        }
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    }

    private HttpRequest post(String path, String token, String body) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-CSRF-TOKEN", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private static String csrfToken(String body) {
        return com.jayway.jsonpath.JsonPath.read(body, "$.token");
    }

    private static String sessionId(CookieManager jar) {
        return jar.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                .findFirst().map(HttpCookie::getValue).orElseThrow();
    }

    @TestConfiguration
    static class AuthBeans {
        @Bean LoginService loginService() { return mock(LoginService.class); }
        @Bean UserSignupService signupService() { return mock(UserSignupService.class); }
        @Bean AuthController authController(LoginService service, SecurityContextRepository contexts,
                CsrfTokenRepository csrfTokens) {
            return new AuthController(service, contexts, csrfTokens);
        }
        @Bean SignupController signupController(UserSignupService service) { return new SignupController(service); }
    }
}
