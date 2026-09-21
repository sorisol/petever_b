package com.petever.api;

import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petever.api.controller.AuthController;
import com.petever.api.entity.User;
import com.petever.api.service.LoginService;
import com.petever.api.service.InvalidCredentialsException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AuthController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("supabase")
class LoginSecurityTests {
    @Autowired MockMvc mvc;
    @MockitoBean LoginService loginService;

    @Test
    void loginNeedsCsrfAndCreatesRevocableSession() throws Exception {
        var user = new User();
        user.id = 42L;
        user.email = "member@example.com";
        user.nickname = "회원";
        when(loginService.authenticate("member@example.com", "valid-password")).thenReturn(user);
        var request = """
                {"email":"member@example.com","password":"valid-password"}
                """;

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isForbidden());
        var login = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andReturn();
        var session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("회원"));
        mvc.perform(post("/api/auth/logout").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isUnauthorized());
    }

    @Test
    void csrfEndpointTokenAuthenticatesLoginAndRotatesSessionId() throws Exception {
        var user = new User();
        user.id = 42L;
        user.email = "member@example.com";
        user.nickname = "회원";
        when(loginService.authenticate("member@example.com", "valid-password")).thenReturn(user);

        var csrfResponse = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrfResponse.getRequest().getSession(false);
        var oldSessionId = session.getId();
        String token = com.jayway.jsonpath.JsonPath.read(csrfResponse.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"member@example.com\",\"password\":\"valid-password\"}"))
                .andExpect(status().isOk());
        assertNotEquals(oldSessionId, session.getId());
        mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isOk());
    }

    @Test
    void invalidCredentialsReturnUniform401WithoutAuthenticatedSession() throws Exception {
        when(loginService.authenticate("missing@example.com", "wrong-password"))
                .thenThrow(new InvalidCredentialsException());
        var response = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"missing@example.com\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 올바르지 않습니다."))
                .andReturn();
        var session = response.getRequest().getSession(false);
        assertTrue(session == null || session.getAttribute("SPRING_SECURITY_CONTEXT") == null);
    }

    @Test
    void anonymousSessionReturns401() throws Exception {
        mvc.perform(get("/api/auth/session")).andExpect(status().isUnauthorized());
    }
}
