package com.petever.api;

import com.petever.api.controller.*;
import com.petever.api.entity.*;
import com.petever.api.repository.*;
import com.petever.api.service.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class AnimalSourceClientTests {
    @Test
    void legacyLossFetchOmitsRegionParametersAndEncodesKey() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var query = new AtomicReference<String>();
        server.createContext("/animals", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] response = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{},\"totalCount\":0}}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var client = new AnimalSourceClient("http://127.0.0.1:" + server.getAddress().getPort() + "/animals", "abc+def");
            client.fetch(LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 14), 1);
            var parameters = queryParameters(query.get());
            assertEquals("abc+def", parameters.get("serviceKey"));
            assertEquals("20260907", parameters.get("bgnde"));
            assertEquals("20260914", parameters.get("ended"));
            assertFalse(parameters.containsKey("endde"));
            assertFalse(parameters.containsKey("upr_cd"));
            assertFalse(parameters.containsKey("org_cd"));
            assertEquals(0, new AnimalSourceParser().parse("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{},\"totalCount\":0}}}").totalCount());
        } finally {
            server.stop(0);
        }
    }
    @Test
    void configuredWithOnlyIntellijLossInfoKey() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("supabase");
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("intellij", Map.of("LOSSINFO_API_KEY", "sample-key")));
            context.register(AnimalSourceClient.class);
            context.refresh();
            assertTrue(context.getBean(AnimalSourceClient.class).configured());
        }
    }

    // AnimalSourceClient 자체는 더 이상 프로덕션 배선 경로가 아니다(AnimalSourceClientConfig가
    // 실제 두 빈을 만든다); 이 테스트는 그 설정 클래스를 직접 검증하며, ANIMAL_API_SERVICE_KEY와
    // LOSSINFO_API_KEY가 둘 다 설정됐을 때 소스별로 어느 환경변수가 우선하는지도 함께 확인한다.
    @Test
    void configResolvesDistinctKeysAndDateParametersPerSource() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var query = new AtomicReference<String>();
        server.createContext("/probe", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] response = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{},\"totalCount\":0}}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/probe";
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().setActiveProfiles("supabase");
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                        "ANIMAL_API_ABANDONMENT_URL", baseUrl,
                        "ANIMAL_API_LOSS_URL", baseUrl,
                        "LOSSINFO_API_KEY", "loss-key",
                        "ANIMAL_API_SERVICE_KEY", "abandonment-key")));
                context.register(AnimalSourceClientConfig.class);
                context.refresh();

                var abandonment = context.getBean("abandonmentSourceClient", AnimalSourceClient.class);
                var loss = context.getBean("lossInfoSourceClient", AnimalSourceClient.class);
                assertTrue(abandonment.configured());
                assertTrue(loss.configured());

                var from = LocalDate.of(2026, 9, 7);
                var to = LocalDate.of(2026, 9, 14);
                abandonment.fetch(from, to, 1);
                var abandonmentQuery = queryParameters(query.get());
                assertEquals("abandonment-key", abandonmentQuery.get("serviceKey"));
                assertEquals("20260907", abandonmentQuery.get("bgnde"));
                assertEquals("20260914", abandonmentQuery.get("endde"));
                assertFalse(abandonmentQuery.containsKey("ended"));

                loss.fetch(from, to, 1);
                var lossQuery = queryParameters(query.get());
                assertEquals("loss-key", lossQuery.get("serviceKey"));
                assertEquals("20260907", lossQuery.get("bgnde"));
                assertEquals("20260914", lossQuery.get("ended"));
                assertFalse(lossQuery.containsKey("endde"));
            }
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> queryParameters(String query) {
        return Arrays.stream(query.split("&"))
                .map(parameter -> parameter.split("=", 2))
                .collect(Collectors.toMap(
                        parameter -> URLDecoder.decode(parameter[0], StandardCharsets.UTF_8),
                        parameter -> URLDecoder.decode(parameter[1], StandardCharsets.UTF_8)));
    }
}
