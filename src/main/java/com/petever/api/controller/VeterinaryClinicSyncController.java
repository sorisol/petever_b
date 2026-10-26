package com.petever.api.controller;

import com.petever.api.service.VeterinaryClinicSourceClient.SyncDisabledException;
import com.petever.api.service.VeterinaryClinicSyncService;
import com.petever.api.service.VeterinaryClinicSyncService.SyncAlreadyRunningException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("supabase")
public class VeterinaryClinicSyncController {
    private final VeterinaryClinicSyncService sync;
    public VeterinaryClinicSyncController(VeterinaryClinicSyncService sync) { this.sync = sync; }

    @PostMapping("/api/admin/veterinary-clinic-sync")
    public VeterinaryClinicSyncService.Result trigger() { return sync.sync(); }

    @ResponseStatus(HttpStatus.CONFLICT)
    @ExceptionHandler(SyncAlreadyRunningException.class)
    String busy() { return "Veterinary clinic sync is already running"; }

    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    @ExceptionHandler(SyncDisabledException.class)
    String disabled() { return "Veterinary clinic sync is disabled"; }
}
