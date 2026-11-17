package com.petever.api;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.petever.api.controller.VeterinaryClinicController;
import com.petever.api.repository.VeterinaryClinicRepository;
import com.petever.api.service.VeterinaryClinicNearbyService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = VeterinaryClinicController.class, properties = {
        "petever.rate-limit.nearby-search.max-requests=2",
        "petever.rate-limit.nearby-search.window-seconds=60"
})
@Import(SecurityConfig.class)
@ActiveProfiles("supabase")
class VeterinaryClinicRateLimitWebTests {
    @Autowired MockMvc mvc;
    @MockitoBean VeterinaryClinicRepository clinics;
    @MockitoBean VeterinaryClinicNearbyService nearby;

    @Test
    void blocksBeforeControllerAfterConfiguredLimit() throws Exception {
        when(nearby.find(anyDouble(), anyDouble(), anyDouble(), anyInt(), anyString()))
                .thenReturn(new VeterinaryClinicNearbyService.Result(List.of(), false));
        String body = """
                {"lat":37.566,"lng":126.978}
                """;

        for (int count = 0; count < 2; count++) {
            mvc.perform(post("/api/veterinary-clinics/nearby-search")
                            .with(request -> { request.setRemoteAddr("203.0.113.50"); return request; })
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }

        mvc.perform(post("/api/veterinary-clinics/nearby-search")
                        .with(request -> { request.setRemoteAddr("203.0.113.50"); return request; })
                        .contentType(MediaType.APPLICATION_JSON).content("{not-json}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().exists("Retry-After"))
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().string("Too many requests"));

        verify(nearby, times(2)).find(anyDouble(), anyDouble(), anyDouble(), anyInt(), anyString());
    }
}
