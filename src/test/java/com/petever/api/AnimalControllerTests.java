package com.petever.api;

import com.petever.api.controller.*;
import com.petever.api.entity.*;
import com.petever.api.repository.*;
import com.petever.api.service.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void detailReturnsExactlyTheWhitelistedPublicFieldsWithoutImageUrls() {
        var animals = mock(AnimalRepository.class);
        var records = mock(AnimalExternalRecordRepository.class);
        var animal = new Animal();
        animal.id = 10L;
        var record = new AnimalExternalRecord();
        record.source = AnimalImportService.SOURCE;
        record.rawPayload = """
            {"noticeNo":"N","srvcTxt":"S","popfile4":"https://example.org/4.jpg","sprtEDate":"20261030",
             "desertionNo":"D","rfidCd":"R","happenDt":"20261001","happenPlace":"P","kindCd":"K",
             "colorCd":"C","age":"A","weight":"W","evntImg":"https://example.org/event.jpg","updTm":"U",
             "endReason":"E","careRegNo":"CR","noticeSdt":"NS","noticeEdt":"NE",
             "popfile1":"https://example.org/1.jpg","processState":"PS","sexCd":"F","neuterYn":"Y",
             "specialMark":"SM","careNm":"CN","careTel":"CT","careAddr":"CA","orgNm":"O","secret":"hidden"}
            """;
        when(animals.findByIdAndVisibility(10L, "PUBLIC")).thenReturn(Optional.of(animal));
        when(records.findByAnimalId(10L)).thenReturn(Optional.of(record));

        var result = new AnimalController(animals, records).detail(10L);
        var json = new ObjectMapper().writeValueAsString(result);

        assertEquals(27, result.publicData().size());
        assertEquals("Y", result.publicData().get("popfile1"));
        assertEquals("Y", result.publicData().get("popfile4"));
        assertEquals("Y", result.publicData().get("evntImg"));
        assertFalse(json.contains("https://example.org"));
        assertFalse(result.publicData().containsKey("secret"));
    }

    @Test
    void lostDetailAllowsOnlyPublicSummaryFieldsEvenWhenRawPayloadContainsPrivateData() {
        var animals = mock(AnimalRepository.class);
        var records = mock(AnimalExternalRecordRepository.class);
        var animal = new Animal();
        animal.id = 11L;
        var record = new AnimalExternalRecord();
        record.source = AnimalImportService.LOSS_SOURCE;
        record.rawPayload = """
            {"kindCd":"믹스견","colorCd":"흰색","sexCd":"F","age":"2살","happenDt":"20261001","orgNm":"서울시",
             "happenPlace":"개인 주소","careNm":"신고자","careTel":"010-0000-0000","careAddr":"개인 주소",
             "callName":"신고자","rfidCd":"private-rfid"}
            """;
        when(animals.findByIdAndVisibility(11L, "PUBLIC")).thenReturn(Optional.of(animal));
        when(records.findByAnimalId(11L)).thenReturn(Optional.of(record));

        var result = new AnimalController(animals, records).detail(11L);

        assertEquals(List.of("kindCd", "colorCd", "sexCd", "age", "happenDt", "orgNm"),
                result.publicData().keySet().stream().toList());
        assertFalse(result.publicData().containsKey("happenPlace"));
        assertFalse(result.publicData().containsKey("careTel"));
        assertFalse(result.publicData().containsKey("rfidCd"));
    }

    @Test
    void malformedPublicPayloadFallsBackToEmptyData() {
        var animals = mock(AnimalRepository.class);
        var records = mock(AnimalExternalRecordRepository.class);
        var animal = new Animal();
        animal.id = 12L;
        var record = new AnimalExternalRecord();
        record.source = AnimalImportService.SOURCE;
        record.rawPayload = "not-json";
        when(animals.findByIdAndVisibility(12L, "PUBLIC")).thenReturn(Optional.of(animal));
        when(records.findByAnimalId(12L)).thenReturn(Optional.of(record));

        assertEquals(0, new AnimalController(animals, records).detail(12L).publicData().size());
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
