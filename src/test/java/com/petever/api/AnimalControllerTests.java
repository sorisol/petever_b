package com.petever.api;

import com.petever.api.controller.*;
import com.petever.api.entity.*;
import com.petever.api.repository.*;
import com.petever.api.service.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.server.ResponseStatusException;

class AnimalControllerTests {
    @Test
    void listFiltersPublicAnimalsAndReturnsLeadImage() {
        var repo = mock(AnimalRepository.class);
        var animal = new Animal();
        animal.id = 7L;
        animal.breedName = "믹스견";
        var image = new AnimalImage();
        image.id = 17L;
        image.url = "https://example.org/7.jpg";
        animal.images = List.of(image);
        when(repo.findPublic("DOG", "PROTECTED", null, PageRequest.of(0, 12, Sort.by(Sort.Direction.DESC, "id"))))
                .thenReturn(new PageImpl<>(List.of(animal)));
        var result = new AnimalController(repo, mock(AnimalExternalRecordRepository.class)).list(0, 12, "DOG", "PROTECTED", null);
        assertEquals("/api/animals/images/17", result.content().getFirst().imageUrl());
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(result));
        assertEquals(0, json.path("number").asInt());
        assertEquals(1, json.path("totalPages").asInt());
    }

    @Test
    void detailReturnsImageProxyPathsInsteadOfExternalUrls() {
        var animals = mock(AnimalRepository.class);
        var records = mock(AnimalExternalRecordRepository.class);
        var animal = new Animal();
        animal.id = 8L;
        var first = new AnimalImage();
        first.id = 21L;
        first.url = "https://example.org/first.jpg";
        var second = new AnimalImage();
        second.id = 22L;
        second.url = "https://example.org/second.jpg";
        animal.images = List.of(first, second);
        when(animals.findByIdAndVisibility(8L, "PUBLIC")).thenReturn(Optional.of(animal));
        when(records.findByAnimalId(8L)).thenReturn(Optional.empty());

        var result = new AnimalController(animals, records).detail(8L);

        assertEquals(List.of("/api/animals/images/21", "/api/animals/images/22"), result.images());
    }

    @Test
    void hiddenAnimalIsNotReturnedByDetail() {
        var repo = mock(AnimalRepository.class);
        when(repo.findByIdAndVisibility(7L, "PUBLIC")).thenReturn(Optional.empty());
        assertThrows(ResponseStatusException.class, () -> new AnimalController(repo, mock(AnimalExternalRecordRepository.class)).detail(7L));
    }
    @Test
    void lostReportIsExplicitlyTypedAndHasNoShelter() {
        var animals = mock(AnimalRepository.class);
        var records = mock(AnimalExternalRecordRepository.class);
        var animal = new Animal();
        animal.id = 9L;
        animal.shelter = null; // 분실 신고는 절대 보호소가 없다; 이 게시 유형은 shelter_id가 nullable이다.
        var record = new AnimalExternalRecord();
        record.animal = animal;
        record.source = AnimalImportService.LOSS_SOURCE;
        when(animals.findPublic(null, null, null, PageRequest.of(0, 12, Sort.by(Sort.Direction.DESC, "id"))))
                .thenReturn(new PageImpl<>(List.of(animal)));
        when(animals.findByIdAndVisibility(9L, "PUBLIC")).thenReturn(Optional.of(animal));
        when(records.findByAnimalIdIn(List.of(9L))).thenReturn(List.of(record));
        when(records.findByAnimalId(9L)).thenReturn(Optional.of(record));

        var controller = new AnimalController(animals, records);
        var page = controller.list(0, 12, null, null, null);
        var detail = controller.detail(9L);

        assertEquals("LOST_REPORT", page.content().getFirst().listingType());
        assertEquals("LOST_REPORT", detail.listingType());
        assertNull(detail.shelterName());
        assertNull(detail.shelterPhone());
    }
}
