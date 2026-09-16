package com.petever.api;

import com.petever.api.controller.*;
import com.petever.api.entity.*;
import com.petever.api.repository.*;
import com.petever.api.service.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

class AnimalSourceClientTests {
    @Test
    void nationalFetchOmitsRegionParametersAndEncodesKey() throws Exception {
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
            assertTrue(query.get().contains("serviceKey=abc%2Bdef"));
            assertTrue(query.get().contains("bgnde=20260907"));
            assertFalse(query.get().contains("upr_cd="));
            assertFalse(query.get().contains("org_cd="));
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

    // AnimalSourceClient itself is no longer the production wiring path (AnimalSourceClientConfig
    // builds the two real beans); this exercises that config class directly, including which env
    // var wins per source when both ANIMAL_API_SERVICE_KEY and LOSSINFO_API_KEY are set.
    @Test
    void configResolvesDistinctKeyPriorityPerSource() throws Exception {
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
                assertTrue(query.get().contains("serviceKey=abandonment-key"));

                loss.fetch(from, to, 1);
                assertTrue(query.get().contains("serviceKey=loss-key"));
            }
        } finally {
            server.stop(0);
        }
    }
}