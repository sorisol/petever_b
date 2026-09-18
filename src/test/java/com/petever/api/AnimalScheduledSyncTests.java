package com.petever.api;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petever.api.service.AnimalScheduledSync;
import com.petever.api.service.AnimalSourceClient;
import com.petever.api.service.AnimalSyncService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

class AnimalScheduledSyncTests {
    @Test
    void registersHourlySupabaseSchedulerAfterOneHour() throws NoSuchMethodException {
        assertNotNull(AnimalScheduledSync.class.getAnnotation(Component.class));
        var profile = AnimalScheduledSync.class.getAnnotation(Profile.class);
        assertNotNull(profile);
        assertTrue(List.of(profile.value()).contains("supabase"));

        var scheduled = AnimalScheduledSync.class.getMethod("run").getAnnotation(Scheduled.class);
        assertNotNull(scheduled);
        assertEquals(3_600_000L, scheduled.fixedDelay());
        assertEquals(3_600_000L, scheduled.initialDelay());
        assertNotNull(PeteverApiApplication.class.getAnnotation(EnableScheduling.class));
    }

    @Test
    void syncsYesterdayThroughTodayWhenAnySourceIsConfigured() {
        var abandonmentClient = mock(AnimalSourceClient.class);
        var lossClient = mock(AnimalSourceClient.class);
        var sync = mock(AnimalSyncService.class);
        when(abandonmentClient.configured()).thenReturn(true);
        LocalDate today = LocalDate.now();
        when(sync.syncNational(today.minusDays(1), today))
                .thenReturn(new AnimalSyncService.Result(List.of()));

        new AnimalScheduledSync(abandonmentClient, lossClient, sync).run();

        verify(sync).syncNational(today.minusDays(1), today);
    }

    @Test
    void skipsWhenBothSourcesAreNotConfigured() {
        var abandonmentClient = mock(AnimalSourceClient.class);
        var lossClient = mock(AnimalSourceClient.class);
        var sync = mock(AnimalSyncService.class);

        new AnimalScheduledSync(abandonmentClient, lossClient, sync).run();

        verify(sync, never()).syncNational(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void syncFailureDoesNotEscapeScheduledInvocation() {
        var abandonmentClient = mock(AnimalSourceClient.class);
        var lossClient = mock(AnimalSourceClient.class);
        var sync = mock(AnimalSyncService.class);
        when(lossClient.configured()).thenReturn(true);
        when(sync.syncNational(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("sync failed"));

        var scheduled = new AnimalScheduledSync(abandonmentClient, lossClient, sync);

        assertDoesNotThrow(scheduled::run);
    }
}
