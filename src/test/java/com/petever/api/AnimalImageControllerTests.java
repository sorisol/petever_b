package com.petever.api;

import com.petever.api.controller.*;
import com.petever.api.entity.*;
import com.petever.api.repository.*;
import com.petever.api.service.*;
import com.petever.api.service.AnimalImageCacheService.CachedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class AnimalImageControllerTests {
    @Test
    void servesFromCacheWithoutFetchingWhenAlreadyCached() {
        var images = mock(AnimalImageRepository.class);
        var cache = mock(AnimalImageCacheService.class);
        var image = new AnimalImage();
        image.id = 1L;
        image.url = "https://example.org/1.jpg";
        when(images.findByIdAndAnimalVisibility(1L, "PUBLIC")).thenReturn(Optional.of(image));
        when(cache.read(1L, "https://example.org/1.jpg")).thenReturn(Optional.of(new CachedImage(new byte[]{1, 2, 3}, "image/jpeg")));

        var response = new AnimalImageController(images, cache).get(1L);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(new byte[]{1, 2, 3}, response.getBody());
        assertEquals("image/jpeg", response.getHeaders().getContentType().toString());
        verify(cache, never()).fetchAndCache(anyLong(), anyString());
        // Content-addressed, not id-only: proves the ETag would change if the same id were later
        // reused for a different source URL (dev schema reset), instead of staying "img-1" forever.
        assertEquals("\"img-" + AnimalImageCacheService.cacheKey(1L, "https://example.org/1.jpg") + "\"",
                response.getHeaders().getETag());
    }

    @Test
    void fetchesAndCachesOnMiss() {
        var images = mock(AnimalImageRepository.class);
        var cache = mock(AnimalImageCacheService.class);
        var image = new AnimalImage();
        image.id = 2L;
        image.url = "https://example.org/2.jpg";
        when(images.findByIdAndAnimalVisibility(2L, "PUBLIC")).thenReturn(Optional.of(image));
        when(cache.read(2L, "https://example.org/2.jpg")).thenReturn(Optional.empty());
        when(cache.fetchAndCache(2L, "https://example.org/2.jpg"))
                .thenReturn(new CachedImage(new byte[]{9}, "image/png"));

        var response = new AnimalImageController(images, cache).get(2L);

        assertEquals(200, response.getStatusCode().value());
        assertArrayEquals(new byte[]{9}, response.getBody());
    }

    @Test
    void returnsNotFoundWhenImageMissingOrAnimalHidden() {
        var images = mock(AnimalImageRepository.class);
        var cache = mock(AnimalImageCacheService.class);
        when(images.findByIdAndAnimalVisibility(3L, "PUBLIC")).thenReturn(Optional.empty());

        var response = new AnimalImageController(images, cache).get(3L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void returnsNotFoundWhenExternalFetchFails() {
        var images = mock(AnimalImageRepository.class);
        var cache = mock(AnimalImageCacheService.class);
        var image = new AnimalImage();
        image.id = 4L;
        image.url = "https://example.org/4.jpg";
        when(images.findByIdAndAnimalVisibility(4L, "PUBLIC")).thenReturn(Optional.of(image));
        when(cache.read(4L, "https://example.org/4.jpg")).thenReturn(Optional.empty());
        when(cache.fetchAndCache(4L, "https://example.org/4.jpg"))
                .thenThrow(new IllegalStateException("boom"));

        var response = new AnimalImageController(images, cache).get(4L);

        assertEquals(404, response.getStatusCode().value());
    }

    @Test
    void returnsNotFoundInsteadOfLeakingA500WhenReadingTheCacheFails() {
        var images = mock(AnimalImageRepository.class);
        var cache = mock(AnimalImageCacheService.class);
        var image = new AnimalImage();
        image.id = 5L;
        image.url = "https://example.org/5.jpg";
        when(images.findByIdAndAnimalVisibility(5L, "PUBLIC")).thenReturn(Optional.of(image));
        when(cache.read(5L, "https://example.org/5.jpg")).thenThrow(new IllegalStateException("disk read failed"));

        var response = new AnimalImageController(images, cache).get(5L);

        assertEquals(404, response.getStatusCode().value());
        verify(cache, never()).fetchAndCache(anyLong(), anyString());
    }

    private static long anyLong() { return org.mockito.ArgumentMatchers.anyLong(); }
    private static String anyString() { return org.mockito.ArgumentMatchers.anyString(); }
}
