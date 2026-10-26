package com.petever.api.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("supabase")
public class VeterinaryClinicSourceClient {
    private static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final String REFERER = "https://file.localdata.go.kr/file/animal_hospitals/info";
    private final boolean enabled;
    private final URI uri;
    private final HttpClient http;
    private final VeterinaryClinicCsvParser parser = new VeterinaryClinicCsvParser();

    public VeterinaryClinicSourceClient(
            @Value("${petever.vet-clinics.sync.enabled:false}") boolean enabled,
            @Value("${petever.vet-clinics.source-url:https://file.localdata.go.kr/file/download/animal_hospitals/info}") String url) {
        this(enabled, URI.create(url), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    VeterinaryClinicSourceClient(boolean enabled, URI uri, HttpClient http) {
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !"file.localdata.go.kr".equalsIgnoreCase(uri.getHost()))
            throw new IllegalArgumentException("Veterinary clinic source must use the official HTTPS host");
        this.enabled = enabled;
        this.uri = uri;
        this.http = http;
    }

    public boolean enabled() { return enabled; }

    public VeterinaryClinicCsvParser.Result fetch() {
        if (!enabled) throw new SyncDisabledException();
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Petever/1.0")
                .header("Referer", REFERER).GET().build();
        try {
            HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200)
                throw new IllegalStateException("Veterinary clinic source HTTP status " + response.statusCode());
            try (InputStream input = response.body(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int total = 0;
                for (int read; (read = input.read(buffer)) != -1;) {
                    total += read;
                    if (total > MAX_BYTES)
                        throw new IllegalStateException("Veterinary clinic source exceeds size limit");
                    output.write(buffer, 0, read);
                }
                return parser.parse(output.toByteArray());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Veterinary clinic source request interrupted", ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Veterinary clinic source request failed", ex);
        }
    }

    public static class SyncDisabledException extends IllegalStateException {}
}
