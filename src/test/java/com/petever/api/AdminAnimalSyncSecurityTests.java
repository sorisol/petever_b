package com.petever.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.petever.api.controller.AnimalSyncController;
import com.petever.api.controller.AuthController;
import com.petever.api.entity.User;
import com.petever.api.service.AnimalSyncService;
import com.petever.api.service.LoginService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {AuthController.class, AnimalSyncController.class})
@Import(SecurityConfig.class)
@ActiveProfiles("supabase")
@TestPropertySource(properties = {
        "ANIMAL_SYNC_USERNAME=legacy-sync",
        "ANIMAL_SYNC_PASSWORD=legacy-password"
})
class AdminAnimalSyncSecurityTests {
    private static final String REQUEST = """
            {"regions":["6110000:6350000"],"from":"2026-09-28","to":"2026-09-29"}
            """;

    @Autowired MockMvc mvc;
    @MockitoBean LoginService loginService;
    @MockitoBean AnimalSyncService animalSyncService;

    @Test
    void adminSessionWithCsrfCanTriggerAnimalSync() throws Exception {
        when(animalSyncService.sync(any())).thenReturn(new AnimalSyncService.Result(List.of()));

        mvc.perform(post("/api/admin/animal-sync")
                        .session(loginAs("ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sources").isArray());
    }

    @Test
    void userSessionCannotTriggerAnimalSync() throws Exception {
        mvc.perform(post("/api/admin/animal-sync")
                        .session(loginAs("USER"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousRequestWithCsrfIsUnauthorized() throws Exception {
        mvc.perform(post("/api/admin/animal-sync")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminSessionWithoutCsrfIsForbidden() throws Exception {
        mvc.perform(post("/api/admin/animal-sync")
                        .session(loginAs("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isForbidden());
    }

    @Test
    void unexpectedDatabaseRoleDoesNotGainAdminAccess() throws Exception {
        mvc.perform(post("/api/admin/animal-sync")
                        .session(loginAs("admin"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isForbidden());
    }

    @Test
    void legacyBasicCredentialsDoNotAuthenticate() throws Exception {
        mvc.perform(post("/api/admin/animal-sync")
                        .with(httpBasic("legacy-sync", "legacy-password"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST))
                .andExpect(status().isUnauthorized());
    }

    private MockHttpSession loginAs(String systemRole) throws Exception {
        var user = new User();
        user.id = 42L;
        user.email = "member@example.com";
        user.nickname = "회원";
        user.systemRole = systemRole;
        when(loginService.authenticate("member@example.com", "valid-password")).thenReturn(user);

        var login = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"member@example.com","password":"valid-password"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) login.getRequest().getSession(false);
    }
}
