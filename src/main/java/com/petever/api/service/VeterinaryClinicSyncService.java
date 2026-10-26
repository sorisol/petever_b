package com.petever.api.service;

import com.petever.api.entity.SyncRun;
import com.petever.api.repository.SyncRunRepository;
import com.petever.api.service.VeterinaryClinicCsvParser.ClinicRow;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("supabase")
public class VeterinaryClinicSyncService {
    public static final String SOURCE = "VET_CLINIC";
    public static final String EXTERNAL_SOURCE = "LOCALDATA_ANIMAL_HOSPITAL";
    private static final Logger log = LoggerFactory.getLogger(VeterinaryClinicSyncService.class);
    private static final int CHUNK_SIZE = 500;
    private static final String UPSERT = """
            INSERT INTO veterinary_clinics
              (external_source, external_id, name, phone, address, region_code,
               latitude, longitude, website_url, operating_status, last_verified_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)
            ON CONFLICT (external_source, external_id) DO UPDATE SET
              name = EXCLUDED.name, phone = EXCLUDED.phone, address = EXCLUDED.address,
              region_code = EXCLUDED.region_code, latitude = EXCLUDED.latitude,
              longitude = EXCLUDED.longitude, operating_status = EXCLUDED.operating_status,
              last_verified_at = EXCLUDED.last_verified_at, updated_at = CURRENT_TIMESTAMP
            WHERE (veterinary_clinics.name, veterinary_clinics.phone, veterinary_clinics.address,
                   veterinary_clinics.region_code, veterinary_clinics.latitude,
                   veterinary_clinics.longitude, veterinary_clinics.operating_status,
                   veterinary_clinics.last_verified_at)
              IS DISTINCT FROM
                  (EXCLUDED.name, EXCLUDED.phone, EXCLUDED.address, EXCLUDED.region_code,
                   EXCLUDED.latitude, EXCLUDED.longitude,
                   EXCLUDED.operating_status, EXCLUDED.last_verified_at)
            """;

    public record Result(Long runId, String status, int fetched, int inserted, int updated,
            int unchanged, int failed, int unknownRegions, int missingCoordinates,
            int invalidCoordinates) {}
    private record Existing(String name, String phone, String address, String regionCode,
            Double latitude, Double longitude, String status, Instant verifiedAt) {}
    private record Pending(ClinicRow row, boolean insert) {}

    private final VeterinaryClinicSourceClient source;
    private final SyncRunRepository runs;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ReentrantLock lock = new ReentrantLock();

    public VeterinaryClinicSyncService(VeterinaryClinicSourceClient source, SyncRunRepository runs,
            JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.source = source;
        this.runs = runs;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    public boolean enabled() { return source.enabled(); }

    public Result sync() {
        if (!enabled()) throw new VeterinaryClinicSourceClient.SyncDisabledException();
        if (!lock.tryLock()) throw new SyncAlreadyRunningException();
        try { return run(); }
        finally { lock.unlock(); }
    }

    public Result syncIfAvailable() {
        if (!enabled() || !lock.tryLock()) return null;
        try { return run(); }
        finally { lock.unlock(); }
    }

    private Result run() {
        SyncRun run = new SyncRun();
        run.source = SOURCE;
        run.status = "RUNNING";
        run.requestScope = "{\"source\":\"LOCALDATA_ANIMAL_HOSPITAL\"}";
        run.startedAt = Instant.now();
        run = runs.saveAndFlush(run);
        int unchanged = 0;
        int unknownRegions = 0;
        int missingCoordinates = 0;
        int invalidCoordinates = 0;
        try {
            VeterinaryClinicCsvParser.Result parsed = source.fetch();
            run.fetchedCount = parsed.totalRows();
            run.failedCount = parsed.failedCount();
            unknownRegions = parsed.unknownRegionCount();
            missingCoordinates = parsed.missingCoordinateCount();
            invalidCoordinates = parsed.invalidCoordinateCount();
            Map<String, Existing> existing = existing();
            List<Pending> pending = new ArrayList<>();
            Instant now = Instant.now();
            for (ClinicRow original : parsed.rows()) {
                Existing old = existing.get(original.externalId());
                Instant verified = original.lastVerifiedAt() != null ? original.lastVerifiedAt()
                        : old != null ? old.verifiedAt() : now;
                ClinicRow row = new ClinicRow(original.externalId(), original.name(), original.address(),
                        original.phone(), original.regionCode(), original.operatingStatus(),
                        original.latitude(), original.longitude(), verified);
                if (old == null) pending.add(new Pending(row, true));
                else if (changed(old, row)) pending.add(new Pending(row, false));
                else unchanged++;
            }
            for (int start = 0; start < pending.size(); start += CHUNK_SIZE) {
                List<Pending> chunk = pending.subList(start, Math.min(start + CHUNK_SIZE, pending.size()));
                try {
                    transactions.executeWithoutResult(status -> jdbc.batchUpdate(UPSERT,
                            chunk.stream().map(VeterinaryClinicSyncService::arguments).toList()));
                    run.insertedCount += (int) chunk.stream().filter(Pending::insert).count();
                    run.updatedCount += (int) chunk.stream().filter(item -> !item.insert()).count();
                } catch (RuntimeException ex) {
                    run.failedCount += chunk.size();
                    run.errorSummary = diagnostic("One or more database batches failed", unknownRegions,
                            missingCoordinates, invalidCoordinates);
                    log.warn("Veterinary clinic batch failed ({})", ex.getClass().getSimpleName());
                }
            }
            if (run.errorSummary == null && (run.failedCount > 0 || unknownRegions > 0))
                run.errorSummary = diagnostic("One or more source records were skipped", unknownRegions,
                        missingCoordinates, invalidCoordinates);
            run.status = run.failedCount == 0 ? "SUCCEEDED" : "PARTIAL";
        } catch (RuntimeException ex) {
            run.status = "FAILED";
            run.errorSummary = "Veterinary clinic source download or parsing failed: " + ex.getClass().getSimpleName();
            log.warn("Veterinary clinic sync failed ({})", ex.getClass().getSimpleName());
        }
        run.finishedAt = Instant.now();
        run = runs.save(run);
        return new Result(run.id, run.status, run.fetchedCount, run.insertedCount, run.updatedCount,
                unchanged, run.failedCount, unknownRegions, missingCoordinates, invalidCoordinates);
    }

    private Map<String, Existing> existing() {
        Map<String, Existing> result = new HashMap<>();
        jdbc.query("""
                SELECT external_id, name, phone, address, region_code, latitude, longitude,
                       operating_status, last_verified_at
                FROM veterinary_clinics WHERE external_source = ?
                """, rs -> {
                    Timestamp timestamp = rs.getTimestamp("last_verified_at");
                    Double latitude = rs.getObject("latitude") == null ? null : rs.getDouble("latitude");
                    Double longitude = rs.getObject("longitude") == null ? null : rs.getDouble("longitude");
                    result.put(rs.getString("external_id"), new Existing(rs.getString("name"),
                            rs.getString("phone"), rs.getString("address"), rs.getString("region_code"),
                            latitude, longitude,
                            rs.getString("operating_status"), timestamp == null ? null : timestamp.toInstant()));
                }, EXTERNAL_SOURCE);
        return result;
    }

    private static boolean changed(Existing old, ClinicRow row) {
        return !Objects.equals(old.name(), row.name()) || !Objects.equals(old.phone(), row.phone())
                || !Objects.equals(old.address(), row.address()) || !Objects.equals(old.regionCode(), row.regionCode())
                || !Objects.equals(old.latitude(), row.latitude()) || !Objects.equals(old.longitude(), row.longitude())
                || !Objects.equals(old.status(), row.operatingStatus())
                || !Objects.equals(old.verifiedAt(), row.lastVerifiedAt());
    }

    private static Object[] arguments(Pending pending) {
        ClinicRow row = pending.row();
        return new Object[] { EXTERNAL_SOURCE, row.externalId(), row.name(), row.phone(), row.address(),
                row.regionCode(), row.latitude(), row.longitude(), row.operatingStatus(),
                Timestamp.from(row.lastVerifiedAt()) };
    }

    private static String diagnostic(String message, int unknownRegions, int missingCoordinates,
            int invalidCoordinates) {
        return message + "; unknownRegionCount=" + unknownRegions
                + "; missingCoordinateCount=" + missingCoordinates
                + "; invalidCoordinateCount=" + invalidCoordinates;
    }

    public static class SyncAlreadyRunningException extends IllegalStateException {}
}
