package com.petever.api;

import com.petever.api.controller.AnimalImageController;
import com.petever.api.repository.AnimalImageRepository;
import com.petever.api.service.AnimalImageCacheService;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// Slice test (no JPA/datasource needed) proving SecurityConfig's permitAll actually covers
// /api/animals/images/* -- PublicRouteSecurityTests was meant to prove this too, but that test
// (and every other @SpringBootTest here) never runs in this sandbox because AnimalController's
// AnimalRepository dependency has no bean under the default profile, a pre-existing issue
// unrelated to this feature (see PublicRouteSecurityTests). @WebMvcTest(controllers = ...)
// scopes the slice to just AnimalImageController, so it never touches AnimalController/AnimalRepository.
@WebMvcTest(controllers = AnimalImageController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("supabase")
class AnimalImageSecuritySliceTests {
    @Autowired
    MockMvc mvc;

    @MockitoBean
    AnimalImageRepository images;

    @MockitoBean
    AnimalImageCacheService cache;

    @Test
    void anonymousRequestIsNotBlockedByAuthentication() throws Exception {
        when(images.findByIdAndAnimalVisibility(1L, "PUBLIC")).thenReturn(Optional.empty());
        // 404 (not 401/403) proves the request reached the controller without authenticating.
        mvc.perform(get("/api/animals/images/1")).andExpect(status().isNotFound());
    }
}
