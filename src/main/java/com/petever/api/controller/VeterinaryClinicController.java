package com.petever.api.controller;

import com.petever.api.entity.VeterinaryClinic;
import com.petever.api.repository.VeterinaryClinicRepository;
import com.petever.api.service.VeterinaryClinicNearbyService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("supabase")
@RequestMapping("/api/veterinary-clinics")
public class VeterinaryClinicController {
    public record Summary(Long id, String name, String address, String phone, String sido,
            String sigungu, String operatingStatus, Double latitude, Double longitude,
            Instant lastVerifiedAt) {}
    public record ClinicPage(List<Summary> content, int number, int totalPages, long totalElements) {}
    public record Region(String sido, List<String> sigungus) {}
    public record NearbyRequest(Double lat, Double lng, Double radiusKm, Integer limit, String status) {}
    public record NearbyResponse(double lat, double lng, double radiusKm, int count,
            boolean truncated, List<VeterinaryClinicNearbyService.Clinic> content) {}

    private final VeterinaryClinicRepository clinics;
    private final VeterinaryClinicNearbyService nearby;

    public VeterinaryClinicController(VeterinaryClinicRepository clinics,
            VeterinaryClinicNearbyService nearby) {
        this.clinics = clinics;
        this.nearby = nearby;
    }

    @GetMapping("/regions")
    @Transactional(readOnly = true)
    public List<Region> regions() {
        Map<String, List<String>> grouped = new TreeMap<>();
        for (String code : clinics.findRegionCodes()) {
            String[] parts = code.split("\\|", 2);
            if (parts.length == 2) grouped.computeIfAbsent(parts[0], ignored -> new ArrayList<>()).add(parts[1]);
        }
        return grouped.entrySet().stream().map(entry -> new Region(entry.getKey(), entry.getValue())).toList();
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ClinicPage list(@RequestParam String sido,
            @RequestParam(required = false) String sigungu,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        sido = regionValue(sido, "sido");
        sigungu = sigungu == null || sigungu.isBlank() ? null : regionValue(sigungu, "sigungu");
        if (status != null && !status.isBlank() && !List.of("OPEN", "CLOSED", "UNKNOWN").contains(status))
            throw new IllegalArgumentException("Invalid status");
        if (status != null && status.isBlank()) status = null;
        if (page < 0 || size < 1 || size > 50) throw new IllegalArgumentException("Invalid pagination");
        Page<VeterinaryClinic> result = clinics.findByFilters(sido, sigungu, status,
                PageRequest.of(page, size, Sort.by("name").ascending().and(Sort.by("id").ascending())));
        List<Summary> content = result.getContent().stream().map(VeterinaryClinicController::summary).toList();
        return new ClinicPage(content, result.getNumber(), result.getTotalPages(), result.getTotalElements());
    }

    @PostMapping("/nearby-search")
    public ResponseEntity<NearbyResponse> nearby(@RequestBody NearbyRequest request) {
        if (request == null || request.lat() == null || request.lng() == null
                || !Double.isFinite(request.lat()) || !Double.isFinite(request.lng()))
            throw new IllegalArgumentException("Location is required");
        if (request.lat() < 30 || request.lat() > 39 || request.lng() < 120 || request.lng() > 135)
            throw new IllegalArgumentException("Location is outside the supported service area");
        double radius = request.radiusKm() == null ? 5 : request.radiusKm();
        int limit = request.limit() == null ? 20 : request.limit();
        if (!Double.isFinite(radius) || radius < 1 || radius > 20 || limit < 1 || limit > 50)
            throw new IllegalArgumentException("Invalid nearby search options");
        String status = request.status() == null || request.status().isBlank() ? "OPEN" : request.status();
        if (!List.of("OPEN", "CLOSED", "UNKNOWN").contains(status))
            throw new IllegalArgumentException("Invalid status");
        VeterinaryClinicNearbyService.Result result = nearby.find(request.lat(), request.lng(), radius, limit, status);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new NearbyResponse(request.lat(), request.lng(), radius, result.clinics().size(),
                        result.truncated(), result.clinics()));
    }

    private static Summary summary(VeterinaryClinic clinic) {
        String[] region = VeterinaryClinicNearbyService.splitRegion(clinic.regionCode);
        return new Summary(clinic.id, clinic.name, clinic.address, clinic.phone, region[0], region[1],
                clinic.operatingStatus,
                clinic.latitude == null ? null : clinic.latitude.doubleValue(),
                clinic.longitude == null ? null : clinic.longitude.doubleValue(),
                clinic.lastVerifiedAt);
    }

    private static String regionValue(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 20 || value.contains("|")
                || !value.matches("[가-힣A-Za-z0-9 .·()-]+"))
            throw new IllegalArgumentException("Invalid " + name);
        return value.trim();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> invalid(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<String> unreadable() {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body("Invalid nearby request");
    }
}
