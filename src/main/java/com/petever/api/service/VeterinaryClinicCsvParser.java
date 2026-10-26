package com.petever.api.service;

import java.nio.charset.Charset;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class VeterinaryClinicCsvParser {
    public record ClinicRow(String externalId, String name, String address, String phone,
            String regionCode, String operatingStatus, Double latitude, Double longitude,
            Instant lastVerifiedAt) {}
    public record Result(List<ClinicRow> rows, int totalRows, int failedCount, int unknownRegionCount,
            int missingCoordinateCount, int invalidCoordinateCount) {}

    private static final Charset MS949 = Charset.forName("MS949");
    private static final DateTimeFormatter UPDATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final Set<String> CURRENT_REGIONS = Set.of(
            "서울특별시", "부산광역시", "대구광역시", "인천광역시", "대전광역시", "울산광역시",
            "세종특별자치시", "경기도", "강원특별자치도", "충청북도", "충청남도", "전북특별자치도",
            "전남광주통합특별시", "경상북도", "경상남도", "제주특별자치도");
    private static final Map<String, String> REGION_ALIASES = aliases();
    private final VeterinaryClinicCoordinateConverter coordinates = new VeterinaryClinicCoordinateConverter();

    public Result parse(byte[] bytes) {
        int offset = bytes.length >= 3 && bytes[0] == (byte) 0xEF
                && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF ? 3 : 0;
        List<List<String>> records = records(new String(bytes, offset, bytes.length - offset, MS949));
        if (records.isEmpty()) throw new IllegalArgumentException("Veterinary clinic CSV is empty");
        List<String> header = records.getFirst();
        if (!header.isEmpty()) header.set(0, header.getFirst().replace("\uFEFF", ""));
        Map<String, Integer> columns = columns(header);
        for (String required : List.of("관리번호", "사업장명", "영업상태명", "데이터갱신시점",
                "도로명주소", "전화번호", "좌표정보(X)", "좌표정보(Y)", "지번주소", "최종수정시점")) {
            if (!columns.containsKey(required))
                throw new IllegalArgumentException("Missing veterinary clinic CSV header: " + required);
        }

        Map<String, ClinicRow> unique = new LinkedHashMap<>();
        int failed = 0;
        int unknownRegions = 0;
        int missingCoordinates = 0;
        int invalidCoordinates = 0;
        int total = Math.max(0, records.size() - 1);
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (record.size() != header.size()) { failed++; continue; }
            String externalId = value(record, columns, "관리번호");
            String name = value(record, columns, "사업장명");
            String roadAddress = value(record, columns, "도로명주소");
            String parcelAddress = value(record, columns, "지번주소");
            String address = roadAddress.isBlank() ? parcelAddress : roadAddress;
            if (externalId.isBlank() || externalId.length() > 100 || name.isBlank() || name.length() > 150
                    || address.isBlank() || address.length() > 500) { failed++; continue; }
            String phone = value(record, columns, "전화번호");
            if (phone.isBlank() || phone.length() > 30) phone = null;
            String region = region(address);
            if (region == null) unknownRegions++;
            VeterinaryClinicCoordinateConverter.Point point;
            String x = value(record, columns, "좌표정보(X)");
            String y = value(record, columns, "좌표정보(Y)");
            if (x.isBlank() || y.isBlank()) {
                point = new VeterinaryClinicCoordinateConverter.Point(null, null);
                missingCoordinates++;
            } else {
                try {
                    point = coordinates.convert(x, y);
                } catch (IllegalArgumentException ex) {
                    point = new VeterinaryClinicCoordinateConverter.Point(null, null);
                    invalidCoordinates++;
                }
            }
            Instant verifiedAt = instant(value(record, columns, "데이터갱신시점"));
            if (verifiedAt == null) verifiedAt = instant(value(record, columns, "최종수정시점"));
            ClinicRow row = new ClinicRow(externalId, name, address, phone, region,
                    status(value(record, columns, "영업상태명")),
                    point.latitude(), point.longitude(), verifiedAt);
            if (unique.put(externalId, row) != null) failed++;
        }
        return new Result(List.copyOf(unique.values()), total, failed, unknownRegions,
                missingCoordinates, invalidCoordinates);
    }

    private static Map<String, Integer> columns(List<String> header) {
        Map<String, Integer> result = new HashMap<>();
        for (int i = 0; i < header.size(); i++) result.put(header.get(i).trim(), i);
        return result;
    }

    private static String value(List<String> record, Map<String, Integer> columns, String name) {
        return record.get(columns.get(name)).trim();
    }

    private static String status(String value) {
        if ("영업/정상".equals(value)) return "OPEN";
        if (Set.of("휴업", "폐업", "취소/말소/만료/정지/중지").contains(value)) return "CLOSED";
        return "UNKNOWN";
    }

    static String region(String address) {
        String[] parts = address.trim().split("\\s+");
        if (parts.length == 0) return null;
        String sido = REGION_ALIASES.getOrDefault(parts[0], parts[0]);
        if (!CURRENT_REGIONS.contains(sido)) return null;
        if ("세종특별자치시".equals(sido)) return sido + "|전체";
        if (parts.length < 2 || !parts[1].matches(".+[시군구]")) return null;
        String result = sido + "|" + parts[1];
        return result.length() <= 20 ? result : null;
    }

    private static Instant instant(String value) {
        if (value.isBlank()) return null;
        try { return LocalDateTime.parse(value, UPDATED_AT).atZone(KOREA).toInstant(); }
        catch (DateTimeParseException ignored) { return null; }
    }

    private static Map<String, String> aliases() {
        Map<String, String> aliases = new HashMap<>();
        alias(aliases, "서울특별시", "서울"); alias(aliases, "부산광역시", "부산");
        alias(aliases, "대구광역시", "대구"); alias(aliases, "인천광역시", "인천");
        alias(aliases, "대전광역시", "대전"); alias(aliases, "울산광역시", "울산");
        alias(aliases, "세종특별자치시", "세종"); alias(aliases, "경기도", "경기");
        alias(aliases, "강원특별자치도", "강원", "강원도"); alias(aliases, "충청북도", "충북");
        alias(aliases, "충청남도", "충남"); alias(aliases, "전북특별자치도", "전북", "전라북도");
        alias(aliases, "전남광주통합특별시", "광주", "광주광역시", "전남", "전라남도");
        alias(aliases, "경상북도", "경북"); alias(aliases, "경상남도", "경남");
        alias(aliases, "제주특별자치도", "제주", "제주도");
        return Map.copyOf(aliases);
    }

    private static void alias(Map<String, String> aliases, String canonical, String... names) {
        for (String name : names) aliases.put(name, canonical);
    }

    private static List<List<String>> records(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') { value.append('"'); i++; }
                else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                row.add(value.toString()); value.setLength(0);
            } else if ((c == '\n' || c == '\r') && !quoted) {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(value.toString()); value.setLength(0);
                if (!(row.size() == 1 && row.getFirst().isEmpty())) rows.add(row);
                row = new ArrayList<>();
            } else value.append(c);
        }
        if (quoted) throw new IllegalArgumentException("Unclosed quote in veterinary clinic CSV");
        if (!row.isEmpty() || !value.isEmpty()) { row.add(value.toString()); rows.add(row); }
        return rows;
    }
}
