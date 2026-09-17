package com.petever.api.service;

import com.petever.api.entity.SyncRun;
import com.petever.api.repository.SyncRunRepository;


import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.postgresql.util.PSQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile("supabase")
public class AnimalSyncService {
    private static final Logger log = LoggerFactory.getLogger(AnimalSyncService.class);
    public record Request(List<String> regions, LocalDate from, LocalDate to) {}
    public record SourceResult(String source, Long runId, String status, int fetched, int inserted, int updated, int failed) {}
    public record Result(List<SourceResult> sources) {}
    private final AnimalSourceClient abandonmentClient;
    private final AnimalSourceClient lossClient;
    private final AnimalImportService importer;
    private final SyncRunRepository runs;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnimalSyncService(
            @Qualifier("abandonmentSourceClient") AnimalSourceClient abandonmentClient,
            @Qualifier("lossInfoSourceClient") AnimalSourceClient lossClient,
            AnimalImportService importer, SyncRunRepository runs) {
        this.abandonmentClient = abandonmentClient;
        this.lossClient = lossClient;
        this.importer = importer;
        this.runs = runs;
    }

    public Result sync(Request request) {
        if (request == null || request.regions() == null || request.regions().isEmpty() || request.regions().size() > 2
                || request.regions().stream().anyMatch(r -> r == null || !r.matches("[0-9]{5,10}:[0-9]{5,10}"))
                || request.regions().stream().distinct().count() != request.regions().size()
                || request.from() == null || request.to() == null || request.from().isAfter(request.to())
                || ChronoUnit.DAYS.between(request.from(), request.to()) > 31)
            throw new IllegalArgumentException("Specify 1-2 distinct province:municipality code pairs and a date range up to 31 days");
        return new Result(List.of(
                runSyncIsolated(AnimalImportService.SOURCE, abandonmentClient, request.regions(), request.from(), request.to(), false),
                runSyncIsolated(AnimalImportService.LOSS_SOURCE, lossClient, request.regions(), request.from(), request.to(), false)));
    }

    public Result syncNational(LocalDate from, LocalDate to) {
        return new Result(List.of(
                runSyncIsolated(AnimalImportService.SOURCE, abandonmentClient, List.of(), from, to, true),
                runSyncIsolated(AnimalImportService.LOSS_SOURCE, lossClient, List.of(), from, to, true)));
    }

    // 소스별 실행을 시작하다가 발생한 실패(예: 최초 sync_runs INSERT 자체의 실패)는
    // runSync 자신의 try/catch가 시작되기 전에 일어나므로, 그대로 두면 이 메서드 밖으로
    // 전파되어 다른 소스의 실행까지 완전히 막아버린다. 그래서 여기서 따로 격리한다.
    private SourceResult runSyncIsolated(String source, AnimalSourceClient client, List<String> regions, LocalDate from, LocalDate to, boolean national) {
        try {
            return runSync(source, client, regions, from, to, national);
        } catch (RuntimeException ex) {
            log.warn("Animal sync failed to start for source={} ({})", source, safeDiagnostic(ex));
            return new SourceResult(source, null, "FAILED", 0, 0, 0, 0);
        }
    }

    private SourceResult runSync(String source, AnimalSourceClient client, List<String> regions, LocalDate from, LocalDate to, boolean national) {
        SyncRun run = new SyncRun();
        run.source = source;
        run.status = "RUNNING";
        run.startedAt = Instant.now();
        run.requestScope = mapper.writeValueAsString(Map.of("regions", regions, "from", from.toString(), "to", to.toString()));
        run = runs.saveAndFlush(run);
        try {
            for (int i = 0; i < (national ? 1 : regions.size()); i++) {
                String region = national ? null : regions.get(i);
                int page = 1;
                int total;
                do {
                    var response = national ? client.fetch(from, to, page) : client.fetch(region, from, to, page);
                    total = response.totalCount();
                    for (JsonNode item : response.items()) {
                        run.fetchedCount++;
                        try {
                            if (importer.importItem(item, national ? null : region.split(":", 2)[1])) run.insertedCount++;
                            else run.updatedCount++;
                        } catch (RuntimeException ex) {
                            run.failedCount++;
                            run.errorSummary = "One or more records could not be imported";
                            log.warn("Animal import failed ({})", safeDiagnostic(ex, item));
                        }
                    }
                    run.lastSuccessfulPage = page;
                    runs.saveAndFlush(run);
                    page++;
                } while (page <= 20 && (page - 1) * 100 < total);
                if ((page - 1) * 100 < total) {
                    run.failedCount++;
                    run.errorSummary = "Page limit reached; narrow the date range";
                }
            }
            run.status = run.failedCount == 0 ? "SUCCEEDED" : "PARTIAL";
        } catch (RuntimeException ex) {
            run.status = run.fetchedCount == 0 ? "FAILED" : "PARTIAL";
            run.errorSummary = "Public API request or response failed";
            log.warn("Animal sync run failed for source={} ({})", source, safeDiagnostic(ex));
        }
        run.finishedAt = Instant.now();
        run = runs.save(run);
        return new SourceResult(source, run.id, run.status, run.fetchedCount, run.insertedCount, run.updatedCount, run.failedCount);
    }
    static String safeDiagnostic(Throwable error) {
        return safeDiagnostic(error, null);
    }

    static String safeDiagnostic(Throwable error, JsonNode item) {
        StringBuilder result = new StringBuilder(error.getClass().getSimpleName());
        if (error instanceof IllegalArgumentException
                && AnimalImportService.MISSING_EXTERNAL_ID.equals(error.getMessage())) {
            result.append(" code=MISSING_DESERTION_NO");
            if (item != null && item.isObject()) {
                String fields = item.properties().stream()
                        .map(Map.Entry::getKey)
                        .filter(name -> name.matches("[A-Za-z0-9_]{1,64}"))
                        .sorted().limit(30)
                        .collect(java.util.stream.Collectors.joining(","));
                result.append(" fields=").append(fields);
            }
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                if (sql.getSQLState() != null && sql.getSQLState().matches("[0-9A-Z]{5}"))
                    result.append(" sqlState=").append(sql.getSQLState());
                if (sql instanceof PSQLException postgres && postgres.getServerErrorMessage() != null) {
                    var server = postgres.getServerErrorMessage();
                    appendIdentifier(result, "table", server.getTable());
                    appendIdentifier(result, "column", server.getColumn());
                    appendIdentifier(result, "constraint", server.getConstraint());
                }
                break;
            }
        }
        return result.toString();
    }

    private static void appendIdentifier(StringBuilder result, String name, String value) {
        if (value != null && value.matches("[A-Za-z0-9_]+"))
            result.append(' ').append(name).append('=').append(value);
    }
}
