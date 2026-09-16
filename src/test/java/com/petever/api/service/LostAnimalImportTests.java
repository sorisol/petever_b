package com.petever.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.petever.api.entity.Animal;
import com.petever.api.entity.AnimalExternalRecord;
import com.petever.api.entity.AnimalImage;
import com.petever.api.entity.Shelter;
import com.petever.api.repository.AnimalExternalRecordRepository;
import com.petever.api.repository.AnimalImageRepository;
import com.petever.api.repository.AnimalRepository;
import com.petever.api.repository.ShelterRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class LostAnimalImportTests {
    private static final String PHOTO = "http://openapi.animal.go.kr/openapi/service/rest/fileDownloadSrvc/files/loss/2026/09/example.jpg";
    private static final String ITEM = """
        {"callName":"private-owner","callTel":"010-private","happenAddr":"private-street",
         "happenAddrDtl":"private-unit","happenPlace":"private-home","specialMark":"private-note",
         "happenDt":"2026-09-14 13:00:00.0","orgNm":"가상시 중구","popfile":"%s",
         "kindCd":"믹스견","colorCd":"흰색","sexCd":"F","age":"2살","rfidCd":"private-rfid"}
        """.formatted(PHOTO);

    @Test
    void importsLostReportWithoutContactOrPreciseLocation() {
        var shelters = mock(ShelterRepository.class);
        var animals = mock(AnimalRepository.class);
        var records = mock(AnimalExternalRecordRepository.class);
        var images = mock(AnimalImageRepository.class);
        var importer = new AnimalImportService(shelters, animals, records, images);

        assertTrue(importer.importItem(new ObjectMapper().readTree(ITEM), null));

        var animalCaptor = ArgumentCaptor.forClass(Animal.class);
        var recordCaptor = ArgumentCaptor.forClass(AnimalExternalRecord.class);
        var imageCaptor = ArgumentCaptor.forClass(AnimalImage.class);
        verify(animals).save(animalCaptor.capture());
        verify(records).save(recordCaptor.capture());
        verify(images).save(imageCaptor.capture());
        Animal animal = animalCaptor.getValue();
        AnimalExternalRecord record = recordCaptor.getValue();
        assertEquals("가상시 중구", animal.foundPlace);
        assertEquals("UNKNOWN", animal.careStatus);
        assertNull(animal.shelter);
        assertEquals("LOSS_INFO", record.source);
        assertTrue(record.externalId.startsWith("LOSS-"));
        assertTrue(imageCaptor.getValue().url.startsWith("https://openapi.animal.go.kr/"));
        assertEquals("PUBLIC_API", imageCaptor.getValue().source);
        assertFalse(record.rawPayload.contains("private-"));
        assertFalse(record.rawPayload.contains("callName"));
        assertFalse(record.rawPayload.contains("happenPlace"));
        assertFalse(record.rawPayload.contains("rfidCd"));
        assertFalse(animal.foundPlace.contains("private-"));
        verify(shelters, never()).save(any(Shelter.class));
    }

    @Test
    void identityIgnoresPhotoUrlButUsesStableReportFacts() {
        var mapper = new ObjectMapper();
        var http = mapper.readTree(ITEM);
        var https = mapper.readTree(ITEM.replace("http://openapi.animal.go.kr/", "https://openapi.animal.go.kr/"));
        assertEquals(AnimalImportService.lossId(http), AnimalImportService.lossId(https));
        var differentPhoto = mapper.readTree(ITEM.replace(PHOTO, PHOTO.replace("example.jpg", "rotated-cdn-path.jpg")));
        assertEquals(AnimalImportService.lossId(http), AnimalImportService.lossId(differentPhoto));
        var anotherDate = mapper.readTree(ITEM.replace("2026-09-14", "2026-09-13"));
        assertFalse(AnimalImportService.lossId(http).equals(AnimalImportService.lossId(anotherDate)));
    }

    @Test
    void rejectsLossReportWithoutStablePhoto() {
        var item = new ObjectMapper().readTree(ITEM.replace(PHOTO, ""));
        var importer = new AnimalImportService(mock(ShelterRepository.class), mock(AnimalRepository.class),
                mock(AnimalExternalRecordRepository.class), mock(AnimalImageRepository.class));
        assertThrows(IllegalArgumentException.class, () -> importer.importItem(item, null));
    }
}