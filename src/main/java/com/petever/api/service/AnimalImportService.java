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
    // "ANIMAL_API"는 이제 두 공공 소스 중 하나(LOSS_INFO와 대비되는 구조동물 공고 API)를
    // 가리키는 이름이다. ABANDONMENT_API로 이름을 바꾸는 안도 고려했지만 기각했다 —
    // 이름을 명확히 하는 것만을 위해 Supabase에 이미 기록된 animal_external_records/
    // sync_runs 행들을 마이그레이션해야 해서, 값은 그대로 둔다.
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
            // 분실 신고(보호소 개념 자체가 없음)와 달리, careRegNo가 없는 구조동물 공고도
            // 실제 물리적인 보호소는 있고 단지 이번 응답에 등록번호가 없을 뿐이다 —
            // null로 만드는 대신 (아래 careNm/Tel/Addr을 담은) "식별은 안 되지만 실존하는"
            // Shelter 행을 유지한다. shelter_id가 nullable인 것은 정말 보호소가 없는
            // 게시물을 위한 것이지, 이 경우를 위한 게 아니다.
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
        animal.shelter = null; // 분실 신고는 보호소가 없다; 이 게시 유형은 shelter_id가 nullable이다.
        String kind = text(item, "kindCd");
        animal.species = lossSpecies(kind);
        animal.breedName = kind;
        animal.sex = switch (fallback(text(item, "sexCd"), "")) {
            case "M" -> "MALE"; case "F" -> "FEMALE"; default -> "UNKNOWN";
        };
        animal.ageDescription = text(item, "age");
        animal.color = text(item, "colorCd");
        animal.foundDate = date(text(item, "happenDt"));
        animal.foundPlace = text(item, "orgNm"); // 신고자 이름·전화번호·주소·정확한 happenPlace는 절대 노출하지 않는다.
        if ("PUBLIC_API".equals(animal.statusAuthority)) animal.careStatus = "UNKNOWN";
        animals.save(animal);

        record.rawPayload = safeLossPayload(item, photoUrl(item));
        record.lastSeenAt = Instant.now();
        record.lastSyncedAt = record.lastSeenAt;
        records.save(record);
        // animal_images.source는 사진을 "누가 올렸는지"만 구분한다(PUBLIC_API / SHELTER);
        // 어떤 공공 API에서 왔는지는 animal_external_records.source로 별도 추적한다.
        images.deleteByAnimalAndSource(animal, "PUBLIC_API");
        AnimalImage image = new AnimalImage();
        image.animal = animal;
        image.source = "PUBLIC_API";
        image.url = photoUrl(item);
        image.sortOrder = 0;
        images.save(image);
        return inserted;
    }

    // 사진 URL은 의도적으로 제외한다: 포털이 같은 신고 건에 대해 이미지 CDN 경로를 바꿀 수
    // 있는데, 포함시키면 재수집 시 새 레코드가 생겨버린다. 이렇게 약해진 식별 방식
    // (안정적인 신고 번호 필드가 알려진 게 없음)은 두 신고가 날짜+지역+품종+성별+색상을
    // 공유하면 여전히 충돌할 수 있다 — 이 한계는 docs/database-design.md 참고.
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
        // 분실 신고에는 신고자 개인정보가 담겨 있으므로, 화면에 필요한 비연락처 필드만 남긴다.
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

    // 실제 응답 형식은 미확인 상태다(확인할 실제 API 접근 권한이 없음) — 숫자만 오는 경우와
    // "3.5(Kg)" 같은 단위 접미사가 붙는 경우 모두 관대하게 처리한다.
    private static final Pattern WEIGHT = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)");

    // animals.weight_kg는 DECIMAL(6,2)다(최대 9999.99). 필드가 잘못 매핑돼(예: 날짜 같은
    // 문자열) 더 큰 숫자로 파싱되더라도 이 컬럼이 오버플로돼 항목 전체의 수집이 실패하면
    // 안 된다.
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
