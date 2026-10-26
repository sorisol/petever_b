package com.petever.api.service;

import com.petever.api.repository.VeterinaryClinicRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("supabase")
public class VeterinaryClinicSyncSchedule {
    private static final Logger log = LoggerFactory.getLogger(VeterinaryClinicSyncSchedule.class);
    private final VeterinaryClinicRepository clinics;
    private final VeterinaryClinicSyncService sync;

    public VeterinaryClinicSyncSchedule(VeterinaryClinicRepository clinics, VeterinaryClinicSyncService sync) {
        this.clinics = clinics;
        this.sync = sync;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initial() {
        if (sync.enabled() && clinics.count() == 0) run("Initial");
    }

    @Scheduled(fixedDelay = 86_400_000, initialDelay = 3_600_000)
    public void scheduled() { run("Scheduled"); }

    private void run(String kind) {
        try {
            VeterinaryClinicSyncService.Result result = sync.syncIfAvailable();
            if (result != null)
                log.info("{} veterinary clinic sync: status={}, fetched={}, inserted={}, updated={}, failed={}",
                        kind, result.status(), result.fetched(), result.inserted(), result.updated(), result.failed());
        } catch (RuntimeException ex) {
            log.warn("{} veterinary clinic sync failed ({})", kind, ex.getClass().getSimpleName());
        }
    }
}
