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

// SecurityConfig의 permitAll이 실제로 /api/animals/images/*를 커버하는지 증명하는 슬라이스
// 테스트(JPA/데이터소스 불필요) -- PublicRouteSecurityTests도 같은 목적이었지만, 그 테스트는
// (그리고 여기 있는 다른 모든 @SpringBootTest도) AnimalController의 AnimalRepository 의존성이
// 기본 프로필에서 빈이 없어 이 샌드박스에서 아예 실행되지 않는다. 이 기능과는 무관한 기존
// 문제다(PublicRouteSecurityTests 참고). @WebMvcTest(controllers = ...)로 AnimalImageController
// 하나에만 슬라이스를 한정해, AnimalController/AnimalRepository는 전혀 건드리지 않는다.
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
        // 404(401/403이 아님)는 인증 없이도 요청이 컨트롤러까지 도달했음을 증명한다.
        mvc.perform(get("/api/animals/images/1")).andExpect(status().isNotFound());
    }
}
