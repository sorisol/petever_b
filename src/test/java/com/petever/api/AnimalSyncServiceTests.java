package com.petever.api;

import com.petever.api.controller.*;
import com.petever.api.entity.*;
import com.petever.api.repository.*;
import com.petever.api.service.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class AnimalSyncServiceTests {
    private static void stubRunPersistence(SyncRunRepository runs) {
        var idGen = new AtomicLong(0);
        when(runs.saveAndFlush(any(SyncRun.class))).thenAnswer(call -> {
            SyncRun run = call.getArgument(0);
            run.id = idGen.incrementAndGet();
            return run;
        });
        when(runs.save(any(SyncRun.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void processesARegionAndRecordsCountsPerSource() {
        var abandonmentClient = mock(AnimalSourceClient.class);
        var lossClient = mock(AnimalSourceClient.class);
        var importer = mock(AnimalImportService.class);
        var runs = mock(SyncRunRepository.class);
        stubRunPersistence(runs);
        var item = new ObjectMapper().readTree("{\"desertionNo\":\"A1\"}");
        var from = LocalDate.of(2026, 9, 1);
        var to = LocalDate.of(2026, 9, 2);
        when(abandonmentClient.fetch("6110000:3220000", from, to, 1)).thenReturn(new AnimalSourceParser.Page(List.of(item), 1));
        when(importer.importItem(eq(item), eq("3220000"))).thenReturn(true);
        when(lossClient.fetch("6110000:3220000", from, to, 1)).thenReturn(new AnimalSourceParser.Page(List.of(), 0));

        var result = new AnimalSyncService(abandonmentClient, lossClient, importer, runs)
                .sync(new AnimalSyncService.Request(List.of("6110000:3220000"), from, to));

        assertEquals(2, result.sources().size());
        var abandonment = result.sources().stream()
                .filter(s -> AnimalImportService.SOURCE.equals(s.source())).findFirst().orElseThrow();
        var loss = result.sources().stream()
                .filter(s -> AnimalImportService.LOSS_SOURCE.equals(s.source())).findFirst().orElseThrow();
        assertEquals("SUCCEEDED", abandonment.status());
        assertEquals(1, abandonment.inserted());
        assertEquals(1, abandonment.fetched());
        assertEquals("SUCCEEDED", loss.status());
        assertEquals(0, loss.fetched());
    }

    @Test
    void rejectsUnboundedScope() {
        var service = new AnimalSyncService(mock(AnimalSourceClient.class), mock(AnimalSourceClient.class),
                mock(AnimalImportService.class), mock(SyncRunRepository.class));
        assertThrows(IllegalArgumentException.class, () -> service.sync(new AnimalSyncService.Request(
                List.of("6110000:3220000", "6260000:3210000", "6270000:3310000"), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 5, 1))));
    }

    @Test
    void nationalSyncUsesRegionlessFetchForBothSources() {
        var abandonmentClient = mock(AnimalSourceClient.class);
        var lossClient = mock(AnimalSourceClient.class);
        var importer = mock(AnimalImportService.class);
        var runs = mock(SyncRunRepository.class);
        stubRunPersistence(runs);
        var abandonmentItem = new ObjectMapper().readTree("{\"desertionNo\":\"N1\"}");
        var lossItem = new ObjectMapper().readTree("{\"popfile\":\"x\"}");
        var from = LocalDate.of(2026, 9, 7);
        var to = LocalDate.of(2026, 9, 14);
        when(abandonmentClient.fetch(from, to, 1)).thenReturn(new AnimalSourceParser.Page(List.of(abandonmentItem), 1));
        when(lossClient.fetch(from, to, 1)).thenReturn(new AnimalSourceParser.Page(List.of(lossItem), 1));
        when(importer.importItem(abandonmentItem, null)).thenReturn(true);
        when(importer.importItem(lossItem, null)).thenReturn(true);

        var result = new AnimalSyncService(abandonmentClient, lossClient, importer, runs).syncNational(from, to);

        assertEquals(2, result.sources().size());
        result.sources().forEach(source -> assertEquals("SUCCEEDED", source.status()));
        verify(abandonmentClient).fetch(from, to, 1);
        verify(lossClient).fetch(from, to, 1);
        verify(importer).importItem(abandonmentItem, null);
        verify(importer).importItem(lossItem, null);
    }

    @Test
    void oneSourceFailingToStartDoesNotBlockTheOtherSource() {
        var abandonmentClient = mock(AnimalSourceClient.class);
        var lossClient = mock(AnimalSourceClient.class);
        var importer = mock(AnimalImportService.class);
        var runs = mock(SyncRunRepository.class);
        // Fail before runSync's own try/catch begins (the initial run-start INSERT), specifically
        // for the loss source, to prove it can't stop the abandonment source from running.
        var idGen = new AtomicLong(0);
        when(runs.saveAndFlush(any(SyncRun.class))).thenAnswer(call -> {
            SyncRun run = call.getArgument(0);
            if (AnimalImportService.LOSS_SOURCE.equals(run.source)) throw new IllegalStateException("db unavailable");
            run.id = idGen.incrementAndGet();
            return run;
        });
        when(runs.save(any(SyncRun.class))).thenAnswer(call -> call.getArgument(0));
        var from = LocalDate.of(2026, 9, 7);
        var to = LocalDate.of(2026, 9, 14);
        when(abandonmentClient.fetch(from, to, 1)).thenReturn(new AnimalSourceParser.Page(List.of(), 0));

        var result = new AnimalSyncService(abandonmentClient, lossClient, importer, runs).syncNational(from, to);

        assertEquals(2, result.sources().size());
        var abandonment = result.sources().stream()
                .filter(s -> AnimalImportService.SOURCE.equals(s.source())).findFirst().orElseThrow();
        var loss = result.sources().stream()
                .filter(s -> AnimalImportService.LOSS_SOURCE.equals(s.source())).findFirst().orElseThrow();
        assertEquals("SUCCEEDED", abandonment.status());
        assertEquals("FAILED", loss.status());
    }
}
