package com.petever.api.service;



import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Value;

// Not a @Component: two beans (one per public API source) are constructed explicitly by
// AnimalSourceClientConfig with distinct URL/key properties. The @Value defaults below only
// matter when a test registers this class directly with Spring (see AnimalSourceClientTests).
public class AnimalSourceClient {
    private final String url;
    private final String serviceKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AnimalSourceParser parser = new AnimalSourceParser();

    public AnimalSourceClient(
            @Value("${ANIMAL_API_URL:https://apis.data.go.kr/1543061/lossInfoService/lossInfo}") String url,
            @Value("${LOSSINFO_API_KEY:${ANIMAL_API_SERVICE_KEY:}}") String serviceKey) {
        this.url = url;
        this.serviceKey = serviceKey;
    }

    public boolean configured() {
        return !url.isBlank() && !serviceKey.isBlank();
    }

    public AnimalSourceParser.Page fetch(String region, LocalDate from, LocalDate to, int page) {
        String[] codes = region.split(":", 2);
        String regionQuery = "&upr_cd=" + encode(codes[0]) + "&org_cd=" + encode(codes[1]);
        return send(regionQuery, from, to, page);
    }

    public AnimalSourceParser.Page fetch(LocalDate from, LocalDate to, int page) {
        return send("", from, to, page);
    }

    private AnimalSourceParser.Page send(String regionQuery, LocalDate from, LocalDate to, int page) {
        if (!configured())
            throw new IllegalStateException("LOSSINFO_API_KEY or ANIMAL_API_SERVICE_KEY is required for sync");
        // Portal keys may arrive already percent-encoded; normalize before encoding the query once.
        String key = serviceKey.contains("%") ? URLDecoder.decode(serviceKey, StandardCharsets.UTF_8) : serviceKey;
        String separator = url.contains("?") ? "&" : "?";
        String query = "serviceKey=" + encode(key) + regionQuery
                + "&bgnde=" + from.toString().replace("-", "")
                + "&endde=" + to.toString().replace("-", "")
                + "&pageNo=" + page + "&numOfRows=100&_type=json";
        var request = HttpRequest.newBuilder(URI.create(url + separator + query))
                .timeout(Duration.ofSeconds(20)).GET().build();
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200)
                throw new IllegalStateException("Public API HTTP status " + response.statusCode());
            return parser.parse(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Public API request interrupted", ex);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Public API request failed", ex);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
