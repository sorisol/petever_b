package com.petever.api.controller;

import com.petever.api.service.AnimalSyncService;


import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("supabase")
public class AnimalSyncController {
    private final AnimalSyncService sync;
    public AnimalSyncController(AnimalSyncService sync) { this.sync = sync; }

    @PostMapping("/api/admin/animal-sync")
    public AnimalSyncService.Result trigger(@RequestBody AnimalSyncService.Request request) { return sync.sync(request); }

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    @ExceptionHandler(IllegalArgumentException.class)
    String invalid(IllegalArgumentException ex) { return ex.getMessage(); }
}
