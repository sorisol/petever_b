package com.petever.api.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@Profile("supabase")
public class VeterinaryClinicNearbyService {
    public record Clinic(Long id, String name, String address, String phone, String sido,
            String sigungu, String operatingStatus, Double latitude, Double longitude,
            Instant lastVerifiedAt, long distanceMeters) {}
    public record Result(List<Clinic> clinics, boolean truncated) {}
    private static final double EARTH_RADIUS_KM = 6371.0088;
    private final JdbcTemplate jdbc;

    public VeterinaryClinicNearbyService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Result find(double latitude, double longitude, double radiusKm, int limit, String status) {
        double latitudeDelta = radiusKm / 111.32;
        double longitudeDelta = radiusKm / (111.32 * Math.cos(Math.toRadians(latitude)));
        List<Clinic> matches = jdbc.query("""
                SELECT id, name, address, phone, region_code, operating_status,
                       latitude, longitude, last_verified_at
                FROM veterinary_clinics
                WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?
                  AND operating_status = ?
                """, (rs, row) -> {
                    String[] region = splitRegion(rs.getString("region_code"));
                    double clinicLatitude = rs.getDouble("latitude");
                    double clinicLongitude = rs.getDouble("longitude");
                    long distance = Math.round(distance(latitude, longitude, clinicLatitude, clinicLongitude) * 1000);
                    return new Clinic(rs.getLong("id"), rs.getString("name"), rs.getString("address"),
                            rs.getString("phone"), region[0], region[1], rs.getString("operating_status"),
                            clinicLatitude, clinicLongitude,
                            rs.getTimestamp("last_verified_at") == null ? null
                                    : rs.getTimestamp("last_verified_at").toInstant(),
                            distance);
                }, latitude - latitudeDelta, latitude + latitudeDelta,
                longitude - longitudeDelta, longitude + longitudeDelta, status).stream()
                .filter(clinic -> clinic.distanceMeters() <= Math.round(radiusKm * 1000))
                .sorted(Comparator.comparingLong(Clinic::distanceMeters).thenComparing(Clinic::id))
                .toList();
        boolean truncated = matches.size() > limit;
        return new Result(matches.subList(0, Math.min(limit, matches.size())), truncated);
    }

    static double distance(double lat1, double lng1, double lat2, double lng2) {
        double latitude = Math.toRadians(lat2 - lat1);
        double longitude = Math.toRadians(lng2 - lng1);
        double a = Math.sin(latitude / 2) * Math.sin(latitude / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(longitude / 2) * Math.sin(longitude / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    public static String[] splitRegion(String regionCode) {
        if (regionCode == null) return new String[] { null, null };
        String[] parts = regionCode.split("\\|", 2);
        return parts.length == 2 ? parts : new String[] { parts[0], null };
    }
}
