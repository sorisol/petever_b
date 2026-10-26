package com.petever.api.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@ExtendWith(OutputCaptureExtension.class)
class VeterinaryClinicNearbyServiceTests {
    @Test
    @SuppressWarnings("unchecked")
    void filtersSortsLimitsAndSafelyMapsMissingRegion() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet farther = row(2L, "먼 병원", "서울 길 2", "서울특별시|종로구", 37.576, 126.978);
        ResultSet closer = row(1L, "가까운 병원", "서울 길 1", null, 37.567, 126.978);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            RowMapper<VeterinaryClinicNearbyService.Clinic> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(farther, 0), mapper.mapRow(closer, 1));
        });

        VeterinaryClinicNearbyService.Result result =
                new VeterinaryClinicNearbyService(jdbc).find(37.566, 126.978, 5, 1, "OPEN");

        assertTrue(result.truncated());
        assertEquals(1, result.clinics().size());
        assertEquals("가까운 병원", result.clinics().getFirst().name());
        assertNull(result.clinics().getFirst().sido());
        assertNull(result.clinics().getFirst().sigungu());
        assertTrue(result.clinics().getFirst().distanceMeters() > 100);
    }

    @Test
    void haversineUsesRealWorldDistance() {
        assertEquals(111.2, VeterinaryClinicNearbyService.distance(37, 127, 38, 127), 0.2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotLogUserCoordinates(CapturedOutput output) {
        JdbcTemplate emptyJdbc = mock(JdbcTemplate.class);
        when(emptyJdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        new VeterinaryClinicNearbyService(emptyJdbc).find(37.123, 127.456, 5, 20, "OPEN");
        org.junit.jupiter.api.Assertions.assertFalse(output.getAll().contains("37.123"));
        org.junit.jupiter.api.Assertions.assertFalse(output.getAll().contains("127.456"));
    }

    private static ResultSet row(long id, String name, String address, String region,
            double latitude, double longitude) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(id);
        when(rs.getString("name")).thenReturn(name);
        when(rs.getString("address")).thenReturn(address);
        when(rs.getString("phone")).thenReturn(null);
        when(rs.getString("region_code")).thenReturn(region);
        when(rs.getString("operating_status")).thenReturn("OPEN");
        when(rs.getDouble("latitude")).thenReturn(latitude);
        when(rs.getDouble("longitude")).thenReturn(longitude);
        when(rs.getTimestamp("last_verified_at")).thenReturn(Timestamp.from(Instant.parse("2026-10-24T13:01:59Z")));
        return rs;
    }
}
