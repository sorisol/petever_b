package com.petever.api.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnimalImageCacheServiceTests {
    private static final byte[] FAKE_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4};
    private static final byte[] FAKE_PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};

    // 테스트는 fetchAndCache()를 로컬 스텁 서버로 향하게 해야 하는데 이는 어쩔 수 없이
    // 루프백이다 -- 그래서 "모든 호스트 허용" 테스트용 생성자를 쓰고, 실제 SSRF 가드
    // (isInternalAddress / 기본 생성자)는 아래에서 별도로 검증한다.
    private static AnimalImageCacheService serviceAllowingLoopback(Path cacheDir) {
        return new AnimalImageCacheService(cacheDir.toString(), address -> false);
    }

    @Test
    void fetchAndCacheStoresBytesSoReadReturnsWithoutRefetching(@TempDir Path tempDir) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var hits = new AtomicInteger();
        server.createContext("/photo.jpg", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, FAKE_JPEG.length);
            try (var out = exchange.getResponseBody()) { out.write(FAKE_JPEG); }
        });
        server.start();
        try {
            var service = serviceAllowingLoopback(tempDir);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/photo.jpg";

            var fetched = service.fetchAndCache(1L, url);
            assertArrayEquals(FAKE_JPEG, fetched.bytes());
            assertEquals("image/jpeg", fetched.contentType());
            assertEquals(1, hits.get());

            var cached = service.read(1L, url);
            assertTrue(cached.isPresent());
            assertArrayEquals(FAKE_JPEG, cached.get().bytes());
            assertEquals("image/jpeg", cached.get().contentType());
            assertEquals(1, hits.get(), "reading from the disk cache must not call the external server again");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cachesAnUnmappedContentTypeViaTheBinPlusMetaFallback(@TempDir Path tempDir) throws Exception {
        // image/bmp는 EXTENSION_BY_CONTENT_TYPE에 항목이 없으므로, 확장자 매핑 경로가 아니라
        // writeCacheFile()/read()의 ".bin" + ".meta" 폴백 경로를 검증한다.
        byte[] bmp = {'B', 'M', 1, 2, 3};
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var hits = new AtomicInteger();
        server.createContext("/photo.bmp", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "image/bmp");
            exchange.sendResponseHeaders(200, bmp.length);
            try (var out = exchange.getResponseBody()) { out.write(bmp); }
        });
        server.start();
        try {
            var service = serviceAllowingLoopback(tempDir);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/photo.bmp";
            service.fetchAndCache(9L, url);

            var cached = service.read(9L, url);
            assertTrue(cached.isPresent(), "the .bin+.meta fallback must be a cache hit on the next read");
            assertArrayEquals(bmp, cached.get().bytes());
            assertEquals("image/bmp", cached.get().contentType());
            assertEquals(1, hits.get(), "a working fallback cache must not refetch from the external server");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readReturnsEmptyWhenNothingCachedYet(@TempDir Path tempDir) {
        assertTrue(serviceAllowingLoopback(tempDir).read(999L, "https://example.org/never-fetched.jpg").isEmpty());
    }

    @Test
    void reusedIdWithADifferentUrlIsNotServedFromTheOldIdsCache(@TempDir Path tempDir) throws Exception {
        // 로컬 개발 환경에서 스키마를 리셋해 identity 시퀀스가 재시작되는 상황을 흉내낸다:
        // id 1이 사진 하나를 가리키다가 삭제되고, 이후 재동기화가 완전히 다른 animal_images
        // 행에 id 1을 재사용한다. 캐시 키에 URL이 포함되어야 낡은 바이트가 서빙되지 않는다.
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/first.jpg", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, FAKE_JPEG.length);
            try (var out = exchange.getResponseBody()) { out.write(FAKE_JPEG); }
        });
        server.start();
        try {
            var service = serviceAllowingLoopback(tempDir);
            String firstUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/first.jpg";
            service.fetchAndCache(1L, firstUrl);

            String secondUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/second.jpg";
            assertTrue(service.read(1L, secondUrl).isEmpty(),
                    "same id but a different source URL must be treated as a cache miss");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonImageContentType(@TempDir Path tempDir) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] html = "<html></html>".getBytes(StandardCharsets.UTF_8);
        server.createContext("/page", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        server.start();
        try {
            var service = serviceAllowingLoopback(tempDir);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/page";
            var ex = assertThrows(IllegalStateException.class, () -> service.fetchAndCache(2L, url));
            assertTrue(ex.getMessage().contains("text/html"));
            assertTrue(service.read(2L, url).isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsJpegBytesServedAsApplicationOctetStream(@TempDir Path tempDir) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/photo", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, FAKE_JPEG.length);
            try (var out = exchange.getResponseBody()) { out.write(FAKE_JPEG); }
        });
        server.start();
        try {
            var service = serviceAllowingLoopback(tempDir);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/photo";

            var fetched = service.fetchAndCache(10L, url);
            assertArrayEquals(FAKE_JPEG, fetched.bytes());
            assertEquals("image/jpeg", fetched.contentType());

            var cached = service.read(10L, url);
            assertTrue(cached.isPresent());
            assertEquals("image/jpeg", cached.get().contentType());
            assertArrayEquals(FAKE_JPEG, cached.get().bytes());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonImageBytesServedAsApplicationOctetStream(@TempDir Path tempDir) throws Exception {
        byte[] html = "<html></html>".getBytes(StandardCharsets.UTF_8);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/not-an-image", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, html.length);
            try (var out = exchange.getResponseBody()) { out.write(html); }
        });
        server.start();
        try {
            var service = serviceAllowingLoopback(tempDir);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/not-an-image";

            var ex = assertThrows(IllegalStateException.class, () -> service.fetchAndCache(11L, url));
            assertTrue(ex.getMessage().contains("image signature"));
            assertTrue(service.read(11L, url).isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void detectsOtherSupportedImageFormatsServedAsApplicationOctetStream(@TempDir Path tempDir) throws Exception {
        assertOctetStreamDetected(tempDir.resolve("png"),
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}, "image/png", 12L);
        assertOctetStreamDetected(tempDir.resolve("gif"),
                "GIF89a".getBytes(StandardCharsets.US_ASCII), "image/gif", 13L);
        assertOctetStreamDetected(tempDir.resolve("webp"),
                new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'}, "image/webp", 14L);
    }

    private static void assertOctetStreamDetected(
            Path cacheDir, byte[] bytes, String expectedContentType, long imageId) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/photo", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var service = serviceAllowingLoopback(cacheDir);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/photo";
            assertEquals(expectedContentType, service.fetchAndCache(imageId, url).contentType());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsNonOkStatus(@TempDir Path tempDir) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/missing", exchange -> exchange.sendResponseHeaders(404, -1));
        server.start();
        try {
            var service = serviceAllowingLoopback(tempDir);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/missing";
            var ex = assertThrows(IllegalStateException.class, () -> service.fetchAndCache(3L, url));
            assertTrue(ex.getMessage().contains("404"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsResponsesOverTheSizeCap(@TempDir Path tempDir) throws Exception {
        byte[] tooLarge = new byte[10 * 1024 * 1024 + 1];
        Arrays.fill(tooLarge, (byte) 1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/huge.jpg", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, tooLarge.length);
            try (var out = exchange.getResponseBody()) { out.write(tooLarge); }
        });
        server.start();
        try {
            var service = serviceAllowingLoopback(tempDir);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/huge.jpg";
            var ex = assertThrows(IllegalStateException.class, () -> service.fetchAndCache(4L, url));
            assertTrue(ex.getMessage().contains("cache limit"));
            assertTrue(service.read(4L, url).isEmpty());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void followsExactlyOneRedirectHop(@TempDir Path tempDir) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect.jpg", exchange -> {
            exchange.getResponseHeaders().add("Location", "/photo.jpg");
            exchange.sendResponseHeaders(302, -1);
        });
        server.createContext("/photo.jpg", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/jpeg");
            exchange.sendResponseHeaders(200, FAKE_JPEG.length);
            try (var out = exchange.getResponseBody()) { out.write(FAKE_JPEG); }
        });
        server.start();
        try {
            var service = serviceAllowingLoopback(tempDir);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect.jpg";
            var fetched = service.fetchAndCache(6L, url);
            assertArrayEquals(FAKE_JPEG, fetched.bytes());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotFollowASecondRedirectHop(@TempDir Path tempDir) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/first.jpg", exchange -> {
            exchange.getResponseHeaders().add("Location", "/second.jpg");
            exchange.sendResponseHeaders(302, -1);
        });
        server.createContext("/second.jpg", exchange -> {
            exchange.getResponseHeaders().add("Location", "/photo.jpg");
            exchange.sendResponseHeaders(302, -1);
        });
        server.start();
        try {
            var service = serviceAllowingLoopback(tempDir);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/first.jpg";
            var ex = assertThrows(IllegalStateException.class, () -> service.fetchAndCache(7L, url));
            assertTrue(ex.getMessage().contains("302"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refetchingWithADifferentContentTypeReplacesTheCachedVariant(@TempDir Path tempDir) throws Exception {
        var contentType = new AtomicInteger(0);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/photo.jpg", exchange -> {
            boolean firstCall = contentType.getAndIncrement() == 0;
            byte[] body = firstCall ? FAKE_JPEG : FAKE_PNG;
            exchange.getResponseHeaders().add("Content-Type", firstCall ? "image/jpeg" : "image/png");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try {
            var service = serviceAllowingLoopback(tempDir);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/photo.jpg";
            service.fetchAndCache(8L, url);
            service.fetchAndCache(8L, url);

            var cached = service.read(8L, url);
            assertTrue(cached.isPresent());
            assertEquals("image/png", cached.get().contentType(),
                    "the stale jpg variant must not shadow the newer png one");
            assertArrayEquals(FAKE_PNG, cached.get().bytes());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesLoopbackHostWithTheRealGuard(@TempDir Path tempDir) {
        var service = new AnimalImageCacheService(tempDir.toString());
        var ex = assertThrows(IllegalStateException.class,
                () -> service.fetchAndCache(5L, "http://127.0.0.1:9/x.jpg"));
        assertTrue(ex.getMessage().contains("internal address"));
    }

    @Test
    void isInternalAddressClassifiesPrivateAndPublicRanges() throws Exception {
        assertTrue(AnimalImageCacheService.isInternalAddress(InetAddress.getByName("127.0.0.1")));
        assertTrue(AnimalImageCacheService.isInternalAddress(InetAddress.getByName("10.1.2.3")));
        assertTrue(AnimalImageCacheService.isInternalAddress(InetAddress.getByName("192.168.1.1")));
        assertTrue(AnimalImageCacheService.isInternalAddress(InetAddress.getByName("169.254.1.1")));
        assertFalse(AnimalImageCacheService.isInternalAddress(InetAddress.getByName("8.8.8.8")));
    }

    @Test
    void isInternalAddressClassifiesModernIpv6UniqueLocalRange() throws Exception {
        assertTrue(AnimalImageCacheService.isInternalAddress(InetAddress.getByName("fd12:3456:789a:1::1")));
        assertTrue(AnimalImageCacheService.isInternalAddress(InetAddress.getByName("fc00::1")));
        assertFalse(AnimalImageCacheService.isInternalAddress(InetAddress.getByName("2001:4860:4860::8888")));
    }
}
