package com.petever.api.service;

import com.petever.api.repository.AnimalRepository;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

@Component
@Profile("supabase")
public class AnimalInitialSync {
    private static final Logger log = LoggerFactory.getLogger(AnimalInitialSync.class);
    private final AnimalRepository animals;
    private final AnimalSourceClient abandonmentClient;
    private final AnimalSourceClient lossClient;
    private final AnimalSyncService sync;

    public AnimalInitialSync(AnimalRepository animals,
            @Qualifier("abandonmentSourceClient") AnimalSourceClient abandonmentClient,
            @Qualifier("lossInfoSourceClient") AnimalSourceClient lossClient,
            AnimalSyncService sync) {
        this.animals = animals;
        this.abandonmentClient = abandonmentClient;
        this.lossClient = lossClient;
        this.sync = sync;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            if ((!abandonmentClient.configured() && !lossClient.configured()) || animals.count() != 0) return;
            LocalDate today = LocalDate.now();
            var result = sync.syncNational(today.minusDays(1), today);
            for (var source : result.sources())
                log.info("Initial animal sync ({}): status={}, fetched={}, inserted={}, failed={}",
                        source.source(), source.status(), source.fetched(), source.inserted(), source.failed());
        } catch (Exception ex) {
            // 선택적 수집이 실패했다고 해서 시작 시점에 공개 API 전체가 죽으면 안 된다.
            log.warn("Initial animal sync failed ({}); server remains available", ex.getClass().getSimpleName());
        }
    }
}
