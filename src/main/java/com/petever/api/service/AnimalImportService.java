package com.petever.api.service;

import com.petever.api.entity.*;
import com.petever.api.repository.*;


import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile("supabase")
public class AnimalImportService {
    // "ANIMAL_API" now names one of two public sources (the rescue-animal notice API,
    // as opposed to LOSS_INFO). Renaming to ABANDONMENT_API was considered but rejected:
    // it would require migrating already-recorded animal_external_records/sync_runs
    // rows in Supabase for a naming-clarity gain only, so the value stays as-is.
    public static final String SOURCE = "ANIMAL_API";
    static final String MISSING_EXTERNAL_ID = "Missing desertionNo";
    public static final String LOSS_SOURCE = "LOSS_INFO";
    private static final String PHOTO_PREFIX = "https://openapi.animal.go.kr/openapi/service/rest/fileDownloadSrvc/files/loss/";
    private final ObjectMapper mapper = new ObjectMapper();
    private final ShelterRepository shelters;
    private final AnimalRepository animals;
    private final AnimalExternalRecordRepository records;
    private final AnimalImageRepository images;

    public AnimalImportService(ShelterRepository shelters, AnimalRepository animals,
                        AnimalExternalRecordRepository records, AnimalImageRepository images) {
        this.shelters = shelters;
        this.animals = animals;
        this.records = records;
        this.images = images;
    }

    @Transactional
    public boolean importItem(JsonNode item, String region) {
        if (!item.has("desertionNo") && (item.has("popfile") || item.has("callName")))
            return importLossItem(item);
        String externalId = text(item, "desertionNo");
        if (externalId == null) throw new IllegalArgumentException(MISSING_EXTERNAL_ID);
        AnimalExternalRecord record = records.findBySourceAndExternalId(SOURCE, externalId).orElse(null);
        boolean inserted = record == null;
        if (inserted) {
            record = new AnimalExternalRecord();
            record.source = SOURCE;
            record.externalId = externalId;
            record.animal = new Animal();
        }
        Animal animal = record.animal;
        String shelterId = text(item, "careRegNo");
        if (shelterId != null) {
            animal.shelter = shelters.findByExternalSourceAndExternalId(SOURCE, shelterId)
                    .orElseGet(Shelter::new);
            animal.shelter.externalSource = SOURCE;
            animal.shelter.externalId = shelterId;
        } else if (animal.shelter == null) {
            // Unlike loss reports (no shelter concept at all), an abandonment listing without
            // careRegNo still has a real physical shelter, just without a registration number in
            // this response — keep the unidentified-but-real Shelter row (with careNm/Tel/Addr
            // below) rather than nulling it out; shelter_id being nullable is about listings that
            // truly have no shelter, not this case.
            animal.shelter = new Shelter();
        }
        Shelter shelter = animal.shelter;
        shelter.name = fallback(text(item, "careNm"), "이름 미확인 보호소");
        shelter.phone = text(item, "careTel");
        shelter.address = text(item, "careAddr");
        shelter.regionCode = region;
        shelter.lastSyncedAt = Instant.now();
        shelters.save(shelter);

        String kind = fallback(text(item, "kindCd"), "");
        animal.species = kind.startsWith("[개]") ? "DOG" : kind.startsWith("[고양이]") ? "CAT"
                : kind.startsWith("[") ? "OTHER" : "UNKNOWN";
        animal.breedName = kind.replaceFirst("^\\[[^]]+\\]\\s*", "");
        animal.sex = switch (fallback(text(item, "sexCd"), "")) {
            case "M" -> "MALE"; case "F" -> "FEMALE"; default -> "UNKNOWN";
        };
        animal.neuterStatus = switch (fallback(text(item, "neuterYn"), "")) {
            case "Y" -> "YES"; case "N" -> "NO"; default -> "UNKNOWN";
        };
        animal.ageDescription = text(item, "age");
        animal.weightKg = weight(text(item, "weight"));
        animal.color = text(item, "colorCd");
        animal.foundDate = date(text(item, "happenDt"));
        animal.foundPlace = text(item, "happenPlace");
        String externalStatus = text(item, "processState");
        if ("PUBLIC_API".equals(animal.statusAuthority)) animal.careStatus = mapStatus(externalStatus);
        animals.save(animal);

        record.noticeNumber = text(item, "noticeNo");
        record.noticeStartDate = date(text(item, "noticeSdt"));
        record.noticeEndDate = date(text(item, "noticeEdt"));
        record.externalStatus = externalStatus;
        record.rawPayload = item.toString();
        record.lastSeenAt = Instant.now();
        record.lastSyncedAt = record.lastSeenAt;
        records.save(record);

        List<String> urls = new ArrayList<>();
        for (String field : List.of("popfile1", "popfile2")) {
            String url = text(item, field);
            if (url != null && (url.startsWith("https://") || url.startsWith("http://")) && !urls.contains(url)) urls.add(url);
        }
        if (item.has("popfile1") || item.has("popfile2")) images.deleteByAnimalAndSource(animal, "PUBLIC_API");
        for (int i = 0; i < urls.size(); i++) {
            var image = new AnimalImage();
            image.animal = animal;
            image.source = "PUBLIC_API";
            image.url = urls.get(i);
            image.sortOrder = i;
            images.save(image);
        }
        return inserted;
    }

    private boolean importLossItem(JsonNode item) {
        String externalId = lossId(item);
        AnimalExternalRecord record = records.findBySourceAndExternalId(LOSS_SOURCE, externalId).orElse(null);
        boolean inserted = record == null;
        if (inserted) {
            record = new AnimalExternalRecord();
            record.source = LOSS_SOURCE;
            record.externalId = externalId;
            record.animal = new Animal();
        }
        Animal animal = record.animal;
        animal.shelter = null; // Loss reports have no shelter; shelter_id is nullable for this listing type.
        String kind = text(item, "kindCd");
        animal.species = lossSpecies(kind);
        animal.breedName = kind;
        animal.sex = switch (fallback(text(item, "sexCd"), "")) {
            case "M" -> "MALE"; case "F" -> "FEMALE"; default -> "UNKNOWN";
        };
        animal.ageDescription = text(item, "age");
        animal.color = text(item, "colorCd");
        animal.foundDate = date(text(item, "happenDt"));
        animal.foundPlace = text(item, "orgNm"); // Never expose caller name, phone, address, or precise happenPlace.
        if ("PUBLIC_API".equals(animal.statusAuthority)) animal.careStatus = "UNKNOWN";
        animals.save(animal);

        record.rawPayload = safeLossPayload(item, photoUrl(item));
        record.lastSeenAt = Instant.now();
        record.lastSyncedAt = record.lastSeenAt;
        records.save(record);
        // animal_images.source only distinguishes who uploaded the photo (PUBLIC_API / SHELTER);
        // which public API it came from is tracked separately via animal_external_records.source.
        images.deleteByAnimalAndSource(animal, "PUBLIC_API");
        AnimalImage image = new AnimalImage();
        image.animal = animal;
        image.source = "PUBLIC_API";
        image.url = photoUrl(item);
        image.sortOrder = 0;
        images.save(image);
        return inserted;
    }

    // Photo URL is deliberately excluded: the portal can rotate the image CDN path for the
    // same report, which would otherwise mint a new record on re-collection. This weaker
    // identity (no known stable report number field is available) can still collide if two
    // reports share date+region+breed+sex+color; see docs/database-design.md for this limitation.
    public static String lossId(JsonNode item) {
        String[] facts = {text(item, "happenDt"), text(item, "orgNm"),
                text(item, "kindCd"), text(item, "sexCd"), text(item, "colorCd")};
        for (int i = 0; i < facts.length; i++) if (facts[i] == null) facts[i] = "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join("\u0001", facts).getBytes(StandardCharsets.UTF_8));
            return "LOSS-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static String photoUrl(JsonNode item) {
        String url = text(item, "popfile");
        if (url != null && url.startsWith("http://openapi.animal.go.kr/"))
            url = "https://" + url.substring("http://".length());
        if (url == null || !url.startsWith(PHOTO_PREFIX))
            throw new IllegalArgumentException("Missing supported loss photo URL");
        return url;
    }

    private String safeLossPayload(JsonNode item, String photo) {
        // Loss reports contain caller PII; retain only non-contact fields needed for display.
        var fields = new LinkedHashMap<String, String>();
        for (String key : List.of("kindCd", "colorCd", "sexCd", "age", "happenDt", "orgNm")) {
            String value = text(item, key);
            if (value != null) fields.put(key, value);
        }
        fields.put("popfile", photo);
        return mapper.writeValueAsString(fields);
    }

    private static String lossSpecies(String kind) {
        if (kind == null) return "UNKNOWN";
        if (kind.contains("고양이") || kind.contains("묘")) return "CAT";
        if (kind.contains("기타축종") || kind.contains("조류") || kind.contains("토끼")) return "OTHER";
        if (kind.contains("견") || kind.contains("테리어") || kind.contains("말티")
                || kind.contains("스피츠") || kind.contains("불독")) return "DOG";
        return "UNKNOWN";
    }
    private static String mapStatus(String state) {
        if (state == null) return "UNKNOWN";
        if (state.contains("보호") || state.contains("공고")) return "PROTECTED";
        if (state.contains("입양")) return "ADOPTED";
        if (state.contains("반환")) return "RETURNED";
        if (state.contains("이관") || state.contains("전출")) return "TRANSFERRED";
        if (state.contains("자연사") || state.contains("안락사")) return "DECEASED";
        if (state.contains("종료")) return "OTHER_CLOSED";
        return "UNKNOWN";
    }

    private static String text(JsonNode item, String key) {
        String value = item.path(key).asText().trim();
        return value.isBlank() || "null".equalsIgnoreCase(value) ? null : value;
    }

    private static String fallback(String value, String replacement) { return value == null ? replacement : value; }

    private static LocalDate date(String value) {
        if (value == null) return null;
        try {
            if (value.length() >= 10 && value.charAt(4) == '-') return LocalDate.parse(value.substring(0, 10));
            return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
        }
        catch (DateTimeParseException ex) { return null; }
    }

    // Real response format unverified (no live API access to confirm); tolerant of a bare
    // number or a unit suffix like "3.5(Kg)".
    private static final Pattern WEIGHT = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");

    // animals.weight_kg is DECIMAL(6,2) (max 9999.99); a mismapped field (e.g. a date-like
    // string) parsing into a larger number must not overflow that column and fail the whole
    // item's import.
    private static final BigDecimal MAX_WEIGHT_KG = new BigDecimal("9999.99");

    private static BigDecimal weight(String value) {
        if (value == null) return null;
        Matcher matcher = WEIGHT.matcher(value);
        if (!matcher.find()) return null;
        try {
            BigDecimal parsed = new BigDecimal(matcher.group(1));
            return parsed.compareTo(MAX_WEIGHT_KG) > 0 ? null : parsed;
        } catch (NumberFormatException ex) { return null; }
    }
}
