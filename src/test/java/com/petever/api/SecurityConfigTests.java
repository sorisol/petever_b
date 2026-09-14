package com.petever.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityConfigTests {

	@Value("${local.server.port}")
	private int port;

	@Test
	void rootRequiresAuthentication() throws Exception {
		assertEquals(401, get("/").statusCode());
	}

	@Test
	void actuatorRequiresAuthentication() throws Exception {
		assertEquals(401, get("/actuator/health").statusCode());
	}

	private HttpResponse<String> get(String path) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
		try (var client = HttpClient.newHttpClient()) {
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}
}
