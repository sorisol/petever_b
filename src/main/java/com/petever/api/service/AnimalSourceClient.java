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

// @Component가 아님: 공공 API 소스마다 하나씩, AnimalSourceClientConfig가 서로 다른 URL/키
// 속성으로 두 빈을 명시적으로 생성한다. 아래 @Value 기본값은 테스트가 이 클래스를 Spring에
// 직접 등록할 때만 의미가 있다(AnimalSourceClientTests 참고).
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
        // 포털이 발급하는 키가 이미 퍼센트 인코딩된 채로 올 수 있으므로, 쿼리를 한 번만
        // 인코딩하기 전에 정규화한다.
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
