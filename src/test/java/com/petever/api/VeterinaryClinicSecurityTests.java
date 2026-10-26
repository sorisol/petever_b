package com.petever.api;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petever.api.controller.VeterinaryClinicController;
import com.petever.api.controller.VeterinaryClinicSyncController;
import com.petever.api.repository.VeterinaryClinicRepository;
import com.petever.api.service.VeterinaryClinicNearbyService;
import com.petever.api.service.VeterinaryClinicSyncService;
import com.petever.api.service.VeterinaryClinicSourceClient.SyncDisabledException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = {VeterinaryClinicController.class, VeterinaryClinicSyncController.class})
@Import(SecurityConfig.class)
@ActiveProfiles("supabase")
class VeterinaryClinicSecurityTests {
    @Autowired MockMvc mvc;
    @MockitoBean VeterinaryClinicRepository clinics;
    @MockitoBean VeterinaryClinicNearbyService nearby;
    @MockitoBean VeterinaryClinicSyncService sync;

    @Test
    void anonymousRegionRequestReachesController() throws Exception {
        when(clinics.findRegionCodes()).thenReturn(List.of("서울특별시|종로구"));
        mvc.perform(get("/api/veterinary-clinics/regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].sido").value("서울특별시"));
    }

    @Test
    void anonymousNearbyPostNeedsNoCsrfAndIsNeverCached() throws Exception {
        when(nearby.find(anyDouble(), anyDouble(), anyDouble(), anyInt(), anyString()))
                .thenReturn(new VeterinaryClinicNearbyService.Result(List.of(), false));

        mvc.perform(post("/api/veterinary-clinics/nearby-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lat":37.566,"lng":126.978,"radiusKm":5,"limit":20}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void invalidOrMalformedLocationReturnsFixedNoStoreError() throws Exception {
        mvc.perform(post("/api/veterinary-clinics/nearby-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lat":0,"lng":0}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"));

        mvc.perform(post("/api/veterinary-clinics/nearby-search")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void adminSyncRequiresAdminSessionAndCsrf() throws Exception {
        when(sync.sync()).thenReturn(new VeterinaryClinicSyncService.Result(
                1L, "SUCCEEDED", 1, 1, 0, 0, 0, 0, 0, 0));

        mvc.perform(post("/api/admin/veterinary-clinic-sync").with(csrf()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/veterinary-clinic-sync").with(user("member").roles("USER")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/veterinary-clinic-sync").with(user("admin").roles("ADMIN")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/veterinary-clinic-sync")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void disabledAdminSyncReturnsServiceUnavailable() throws Exception {
        when(sync.sync()).thenThrow(new SyncDisabledException());
        mvc.perform(post("/api/admin/veterinary-clinic-sync")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isServiceUnavailable());
    }
}
