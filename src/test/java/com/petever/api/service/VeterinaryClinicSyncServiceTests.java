package com.petever.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petever.api.entity.SyncRun;
import com.petever.api.repository.SyncRunRepository;
import com.petever.api.service.VeterinaryClinicCsvParser.ClinicRow;
import com.petever.api.service.VeterinaryClinicSourceClient.SyncDisabledException;
import com.petever.api.service.VeterinaryClinicSyncService.SyncAlreadyRunningException;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class VeterinaryClinicSyncServiceTests {
    private final VeterinaryClinicSourceClient source = mock(VeterinaryClinicSourceClient.class);
    private final SyncRunRepository runs = mock(SyncRunRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final VeterinaryClinicSyncService service =
            new VeterinaryClinicSyncService(source, runs, jdbc, transactions);

    @Test
    void disabledSyncFailsBeforeCreatingRun() {
        when(source.enabled()).thenReturn(false);
        assertThrows(SyncDisabledException.class, service::sync);
        verify(runs, never()).saveAndFlush(any());
    }

    @Test
    void insertsInFiveHundredRowChunksAndPersistsRunBeforeDownload() {
        setupPersistence();
        when(source.enabled()).thenReturn(true);
        List<ClinicRow> rows = java.util.stream.IntStream.range(0, 501)
                .mapToObj(index -> row("id-" + index, 37.5, 127.0)).toList();
        when(source.fetch()).thenReturn(new VeterinaryClinicCsvParser.Result(rows, 501, 0, 0, 0, 0));

        VeterinaryClinicSyncService.Result result = service.sync();

        assertEquals("SUCCEEDED", result.status());
        assertEquals(501, result.inserted());
        verify(transactions, times(2)).executeWithoutResult(any());
        verify(jdbc, times(2)).batchUpdate(anyString(), any(List.class));
        var order = inOrder(runs, source);
        order.verify(runs).saveAndFlush(any(SyncRun.class));
        order.verify(source).fetch();
    }

    @Test
    void failedChunkIsRecordedAsPartial() {
        setupPersistence();
        when(source.enabled()).thenReturn(true);
        when(source.fetch()).thenReturn(new VeterinaryClinicCsvParser.Result(
                List.of(row("id-1", 37.5, 127.0)), 1, 0, 0, 0, 0));
        doThrow(new IllegalStateException("database unavailable"))
                .when(transactions).executeWithoutResult(any());

        VeterinaryClinicSyncService.Result result = service.sync();

        assertEquals("PARTIAL", result.status());
        assertEquals(1, result.failed());
        assertEquals(0, result.inserted());
    }

    @Test
    void concurrentSyncIsRejected() throws Exception {
        setupPersistence();
        when(source.enabled()).thenReturn(true);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(source.fetch()).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            return new VeterinaryClinicCsvParser.Result(List.of(), 0, 0, 0, 0, 0);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(service::sync);
            entered.await(5, TimeUnit.SECONDS);
            assertThrows(SyncAlreadyRunningException.class, service::sync);
            release.countDown();
            assertEquals("SUCCEEDED", first.get(5, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void identicalExistingRowIsUnchangedIncludingCoordinates() throws Exception {
        setupPersistence();
        when(source.enabled()).thenReturn(true);
        ClinicRow row = row("same", 37.5, 127.0);
        when(source.fetch()).thenReturn(new VeterinaryClinicCsvParser.Result(
                List.of(row), 1, 0, 0, 0, 0));
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("external_id")).thenReturn("same");
        when(rs.getString("name")).thenReturn(row.name());
        when(rs.getString("phone")).thenReturn(row.phone());
        when(rs.getString("address")).thenReturn(row.address());
        when(rs.getString("region_code")).thenReturn(row.regionCode());
        when(rs.getObject("latitude")).thenReturn(java.math.BigDecimal.valueOf(row.latitude()));
        when(rs.getObject("longitude")).thenReturn(java.math.BigDecimal.valueOf(row.longitude()));
        when(rs.getDouble("latitude")).thenReturn(row.latitude());
        when(rs.getDouble("longitude")).thenReturn(row.longitude());
        when(rs.getString("operating_status")).thenReturn(row.operatingStatus());
        when(rs.getTimestamp("last_verified_at")).thenReturn(Timestamp.from(row.lastVerifiedAt()));
        doAnswer(invocation -> {
            RowCallbackHandler handler = invocation.getArgument(1);
            handler.processRow(rs);
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        VeterinaryClinicSyncService.Result result = service.sync();

        assertEquals(1, result.unchanged());
        assertEquals(0, result.inserted());
        assertEquals(0, result.updated());
        verify(transactions, never()).executeWithoutResult(any());
    }

    @Test
    void sourceClientRejectsNonOfficialHost() {
        assertThrows(IllegalArgumentException.class, () -> new VeterinaryClinicSourceClient(
                true, URI.create("https://example.com/clinics.csv"), mock(HttpClient.class)));
    }

    private void setupPersistence() {
        when(runs.saveAndFlush(any())).thenAnswer(invocation -> {
            SyncRun run = invocation.getArgument(0);
            run.id = 1L;
            return run;
        });
        when(runs.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<TransactionStatus> work = invocation.getArgument(0);
            work.accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
    }

    private static ClinicRow row(String id, double latitude, double longitude) {
        return new ClinicRow(id, "병원 " + id, "서울특별시 종로구 길 1", null,
                "서울특별시|종로구", "OPEN", latitude, longitude, Instant.parse("2026-10-24T13:01:59Z"));
    }
}
