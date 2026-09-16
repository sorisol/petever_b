package com.petever.api;

import com.petever.api.controller.*;
import com.petever.api.entity.*;
import com.petever.api.repository.*;
import com.petever.api.service.*;


import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PublicRouteSecurityTests {
    @Value("${local.server.port}") int port;

    @Test
    void onlyAnimalGetIsPublicAndSyncRemainsProtected() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var list = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/animals")).GET().build();
            // No data profile means no animal controller: 404 proves the route passed security.
            assertEquals(404, client.send(list, HttpResponse.BodyHandlers.discarding()).statusCode());
            var sync = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/admin/animal-sync"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")) .build();
            assertEquals(401, client.send(sync, HttpResponse.BodyHandlers.discarding()).statusCode());
        }
    }
}
