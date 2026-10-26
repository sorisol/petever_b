package com.petever.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.Charset;
import org.junit.jupiter.api.Test;

class VeterinaryClinicCoordinateConverterTests {
    private final VeterinaryClinicCoordinateConverter converter = new VeterinaryClinicCoordinateConverter();

    @Test
    void convertsIndependentEpsg5174FixturesWithinThirtyMeters() {
        assertPoint("197364.614", "453303.294", 37.5820263, 126.9709518);
        assertPoint("205790.749", "407536.638", 37.1696470, 127.0659924);
        assertPoint("181327.812", "-11500.24", 33.3925756, 126.8001260);
    }

    @Test
    void parserKeepsRowsWithoutCoordinatesAndCountsThemSeparately() {
        String csv = "관리번호,사업장명,영업상태명,데이터갱신시점,도로명주소,전화번호,"
                + "좌표정보(X),좌표정보(Y),지번주소,최종수정시점\r\n"
                + "A-1,행복동물병원,영업/정상,2026-10-24 22:01:59,서울특별시 종로구 길 1,"
                + "02-123-4567,,,서울특별시 종로구 길 1,2026-10-24 22:01:59\r\n";

        VeterinaryClinicCsvParser.Result result =
                new VeterinaryClinicCsvParser().parse(csv.getBytes(Charset.forName("MS949")));

        assertEquals(1, result.rows().size());
        assertEquals(1, result.missingCoordinateCount());
        assertEquals(0, result.invalidCoordinateCount());
        assertNull(result.rows().getFirst().latitude());
        assertNull(result.rows().getFirst().longitude());
    }

    @Test
    void parserHandlesBomQuotedCommaNewlineEscapedQuoteAndRegionAlias() {
        String csv = "관리번호,사업장명,영업상태명,데이터갱신시점,도로명주소,전화번호,"
                + "좌표정보(X),좌표정보(Y),지번주소,최종수정시점\r\n"
                + "A-2,\"행복, \"\"동물\"\" 병원\",영업/정상,2026-10-24 22:01:59,"
                + "\"강원도 춘천시 길\n1\",,197364.614,453303.294,,2026-10-24 22:01:59\r\n";

        VeterinaryClinicCsvParser.Result result =
                new VeterinaryClinicCsvParser().parse(withUtf8Bom(csv.getBytes(Charset.forName("MS949"))));

        assertEquals(1, result.rows().size());
        assertEquals("행복, \"동물\" 병원", result.rows().getFirst().name());
        assertEquals("강원특별자치도|춘천시", result.rows().getFirst().regionCode());
        assertEquals(0, result.failedCount());
    }

    @Test
    void parserRejectsHtmlAndMissingHeaders() {
        assertThrows(IllegalArgumentException.class,
                () -> new VeterinaryClinicCsvParser().parse("<html>error</html>".getBytes(Charset.forName("MS949"))));
    }

    private void assertPoint(String x, String y, double expectedLatitude, double expectedLongitude) {
        VeterinaryClinicCoordinateConverter.Point point = converter.convert(x, y);
        assertEquals(expectedLatitude, point.latitude(), 0.00027);
        assertEquals(expectedLongitude, point.longitude(), 0.00034);
    }

    private static byte[] withUtf8Bom(byte[] content) {
        byte[] result = new byte[content.length + 3];
        result[0] = (byte) 0xEF;
        result[1] = (byte) 0xBB;
        result[2] = (byte) 0xBF;
        System.arraycopy(content, 0, result, 3, content.length);
        return result;
    }
}
