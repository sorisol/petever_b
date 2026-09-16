package com.petever.api;

import com.petever.api.controller.*;
import com.petever.api.entity.*;
import com.petever.api.repository.*;
import com.petever.api.service.*;


import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

class AnimalInitialSyncTests {
    private static AnimalSyncService.Result twoSourceResult() {
        return new AnimalSyncService.Result(List.of(
                new AnimalSyncService.SourceResult(AnimalImportService.SOURCE, 1L, "SUCCEEDED", 1, 1, 0, 0),
                new AnimalSyncService.SourceResult(AnimalImportService.LOSS_SOURCE, 2L, "SUCCEEDED", 0, 0, 0, 0)));
    }

    @Test
    void syncsEmptyDatabaseWhenKeyIsConfigured() {
        var animals = mock(AnimalRepository.class);
        var abandonmentClient = mock(AnimalSourceClient.class);
        var lossClient = mock(AnimalSourceClient.class);
        var sync = mock(AnimalSyncService.class);
        when(abandonmentClient.configured()).thenReturn(true);
        when(lossClient.configured()).thenReturn(true);
        when(animals.count()).thenReturn(0L);
        when(sync.syncNational(any(), any())).thenReturn(twoSourceResult());

        new AnimalInitialSync(animals, abandonmentClient, lossClient, sync).onReady();

        verify(sync).syncNational(any(), any());
    }

    @Test
    void syncsWhenOnlyOneSourceIsConfigured() {
        var animals = mock(AnimalRepository.class);
        var abandonmentClient = mock(AnimalSourceClient.class);
        var lossClient = mock(AnimalSourceClient.class);
        var sync = mock(AnimalSyncService.class);
        when(abandonmentClient.configured()).thenReturn(false);
        when(lossClient.configured()).thenReturn(true);
        when(animals.count()).thenReturn(0L);
        when(sync.syncNational(any(), any())).thenReturn(twoSourceResult());

        new AnimalInitialSync(animals, abandonmentClient, lossClient, sync).onReady();

        verify(sync).syncNational(any(), any());
    }

    @Test
    void skipsWhenAnimalsAlreadyExist() {
        var animals = mock(AnimalRepository.class);
        var abandonmentClient = mock(AnimalSourceClient.class);
        var lossClient = mock(AnimalSourceClient.class);
        var sync = mock(AnimalSyncService.class);
        when(abandonmentClient.configured()).thenReturn(true);
        when(lossClient.configured()).thenReturn(true);
        when(animals.count()).thenReturn(3L);

        new AnimalInitialSync(animals, abandonmentClient, lossClient, sync).onReady();

        verify(sync, never()).syncNational(any(), any());
    }

    @Test
    void skipsWhenKeyIsMissing() {
        var animals = mock(AnimalRepository.class);
        var abandonmentClient = mock(AnimalSourceClient.class);
        var lossClient = mock(AnimalSourceClient.class);
        var sync = mock(AnimalSyncService.class);

        new AnimalInitialSync(animals, abandonmentClient, lossClient, sync).onReady();

        verify(animals, never()).count();
        verify(sync, never()).syncNational(any(), any());
    }
    @Test
    void databaseFailureDoesNotStopStartup() {
        var animals = mock(AnimalRepository.class);
        var abandonmentClient = mock(AnimalSourceClient.class);
        var lossClient = mock(AnimalSourceClient.class);
        var sync = mock(AnimalSyncService.class);
        when(abandonmentClient.configured()).thenReturn(true);
        when(lossClient.configured()).thenReturn(true);
        when(animals.count()).thenThrow(new IllegalStateException("database unavailable"));

        assertDoesNotThrow(() -> new AnimalInitialSync(animals, abandonmentClient, lossClient, sync).onReady());
        verify(sync, never()).syncNational(any(), any());
    }

    @Test
    void syncFailureDoesNotStopStartup() {
        var animals = mock(AnimalRepository.class);
        var abandonmentClient = mock(AnimalSourceClient.class);
        var lossClient = mock(AnimalSourceClient.class);
        var sync = mock(AnimalSyncService.class);
        when(abandonmentClient.configured()).thenReturn(true);
        when(lossClient.configured()).thenReturn(true);
        when(sync.syncNational(any(), any())).thenThrow(new IllegalStateException("sync run insert failed"));

        assertDoesNotThrow(() -> new AnimalInitialSync(animals, abandonmentClient, lossClient, sync).onReady());
    }
}
