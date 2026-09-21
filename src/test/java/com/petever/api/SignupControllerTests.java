package com.petever.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.petever.api.controller.SignupController;
import com.petever.api.entity.User;
import com.petever.api.service.DuplicateEmailException;
import com.petever.api.service.SignupValidationException;
import com.petever.api.service.UserSignupService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = SignupController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("supabase")
class SignupControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean UserSignupService signups;

    @Test
    void returnsCreatedUserWithoutPasswordOrHash() throws Exception {
        var user = new User();
        user.id = 42L;
        user.email = "user@example.com";
        user.nickname = "회원";
        user.passwordHash = "never-return-this-hash";
        when(signups.signup(any(), any(), any(), any(), any())).thenReturn(user);

        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.nickname").value("회원"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void returnsFieldErrorsForInvalidInput() throws Exception {
        when(signups.signup(any(), any(), any(), any(), any()))
                .thenThrow(new SignupValidationException(Map.of("password", "비밀번호 오류")));

        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.fieldErrors.password").value("비밀번호 오류"));
    }

    @Test
    void returnsConflictForDuplicateEmail() throws Exception {
        when(signups.signup(any(), any(), any(), any(), any()))
                .thenThrow(new DuplicateEmailException());

        mvc.perform(post("/api/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRequest()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    private static String validRequest() {
        return """
                {"email":"user@example.com","password":"valid-password",
                 "passwordConfirmation":"valid-password","nickname":"회원"}
                """;
    }
}
