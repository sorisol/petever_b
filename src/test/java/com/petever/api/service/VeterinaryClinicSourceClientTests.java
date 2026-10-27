package com.petever.api.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class VeterinaryClinicSourceClientTests {
    @Test
    void springSelectsTheConfiguredConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("supabase");
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test", Map.of("petever.vet-clinics.sync.enabled", "true")));
            context.register(VeterinaryClinicSourceClient.class);
            context.refresh();

            assertTrue(context.getBean(VeterinaryClinicSourceClient.class).enabled());
        }
    }
}
