package com.petever.api.service;

import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("supabase")
public class AnimalScheduledSync {
    private static final Logger log = LoggerFactory.getLogger(AnimalScheduledSync.class);
    private final AnimalSourceClient abandonmentClient;
    private final AnimalSourceClient lossClient;
    private final AnimalSyncService sync;

    public AnimalScheduledSync(
            @Qualifier("abandonmentSourceClient") AnimalSourceClient abandonmentClient,
            @Qualifier("lossInfoSourceClient") AnimalSourceClient lossClient,
            AnimalSyncService sync) {
        this.abandonmentClient = abandonmentClient;
        this.lossClient = lossClient;
        this.sync = sync;
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    public void run() {
        try {
            if (!abandonmentClient.configured() && !lossClient.configured()) return;
            LocalDate today = LocalDate.now();
            var result = sync.syncNational(today.minusDays(1), today);
            for (var source : result.sources())
                log.info("Scheduled animal sync ({}): status={}, fetched={}, inserted={}, failed={}",
                        source.source(), source.status(), source.fetched(), source.inserted(), source.failed());
        } catch (Exception ex) {
            log.warn("Scheduled animal sync failed ({})", ex.getClass().getSimpleName());
        }
    }
}
