package com.petever.api.controller;

import com.petever.api.entity.AnimalImage;
import com.petever.api.repository.AnimalImageRepository;
import com.petever.api.service.AnimalImageCacheService;
import com.petever.api.service.AnimalImageCacheService.CachedImage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.context.annotation.Profile;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

// Serves animal photos through our own server instead of the external public-API host the
// browser used to hit directly (see docs/superpowers/specs/2026-09-15-animal-image-proxy-cache-design.md).
// Must stay reachable anonymously (SecurityConfig permits GET /api/animals/images/*) since
// plain <img> requests carry no auth.
@RestController
@Profile("supabase")
@RequestMapping("/api/animals/images")
public class AnimalImageController {
    private static final Logger log = LoggerFactory.getLogger(AnimalImageController.class);

    private final AnimalImageRepository images;
    private final AnimalImageCacheService cache;

    public AnimalImageController(AnimalImageRepository images, AnimalImageCacheService cache) {
        this.images = images;
        this.cache = cache;
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@PathVariable long id) {
        AnimalImage image = images.findByIdAndAnimalVisibility(id, "PUBLIC").orElse(null);
        if (image == null) return ResponseEntity.notFound().build();

        CachedImage cached;
        try {
            // read() can itself throw (e.g. a concurrent cache-cleanup deletes the file between
            // Files.exists and Files.readAllBytes) -- it must fail the same way fetchAndCache does,
            // not leak a 500 past the "failure = 404" contract this endpoint promises callers.
            cached = cache.read(id, image.url).orElseGet(() -> cache.fetchAndCache(id, image.url));
        } catch (RuntimeException ex) {
            log.warn("Failed to fetch/cache animal image {}: {}", id, ex.getMessage());
            return ResponseEntity.notFound().build();
        }

        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(cached.contentType());
        } catch (InvalidMediaTypeException ex) {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                // Content-addressed (id + source URL hash), not id-only: an id-only ETag would
                // keep returning a 304 for up to 7 days after id-reuse (dev schema reset) or a
                // source-side content change swaps in different bytes for the same id.
                .eTag("\"img-" + AnimalImageCacheService.cacheKey(id, image.url) + "\"")
                .body(cached.bytes());
    }
}
