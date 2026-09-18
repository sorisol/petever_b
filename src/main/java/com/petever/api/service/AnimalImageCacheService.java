package com.petever.api.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

// animal_images.url(외부 공공 API 파일)을 로컬 디스크에 캐시한다. 캐시 키는 animal_images.id +
// 원본 URL의 짧은 해시로 구성해, 브라우저가 (느리고 캐시되지 않는) 외부 호스트에 직접 접근하지
// 않도록 한다. 키에 id뿐 아니라 URL 해시도 포함하는 이유는, 로컬 개발 환경에서 스키마를 재생성해
// id 시퀀스가 처음부터 다시 시작될 때 재사용된 id에 낡은 파일이 잘못 서빙되는 사고(이 기능이
// 노리는 로컬 개발 환경에서 실제로 벌어질 수 있는 위험)를 막기 위해서다. 디스크 캐시는 재배포 시
// 유지되지 않고 인스턴스 간에도 공유되지 않는다 — 호스팅 대상이 아직 정해지지 않았으므로
// (docs/architecture.md 참고) 현재로서는 감수한다. 이 트레이드오프로 인한 알려진 제약 목록은
// 설계 문서를 참고한다.
@Service
@Profile("supabase")
public class AnimalImageCacheService {
    private static final Logger log = LoggerFactory.getLogger(AnimalImageCacheService.class);
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    // LinkedHashMap: read()가 후보 확장자를 순서대로 탐색하므로 순회 순서가 JVM마다 달라지면
    // 안 된다 — Map.of(...)는 이 보장이 없다.
    private static final Map<String, String> EXTENSION_BY_CONTENT_TYPE = new LinkedHashMap<>();
    static {
        EXTENSION_BY_CONTENT_TYPE.put("image/jpeg", "jpg");
        EXTENSION_BY_CONTENT_TYPE.put("image/png", "png");
        EXTENSION_BY_CONTENT_TYPE.put("image/gif", "gif");
        EXTENSION_BY_CONTENT_TYPE.put("image/webp", "webp");
    }

    public record CachedImage(byte[] bytes, String contentType) {}

    private final Path cacheDir;
    private final Predicate<InetAddress> disallowedHost;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Autowired
    public AnimalImageCacheService(@Value("${IMAGE_CACHE_DIR:./data/image-cache}") String cacheDir) {
        this(cacheDir, AnimalImageCacheService::isInternalAddress);
    }

    // 테스트를 위해 공개함: AnimalImageCacheServiceTests가 실제 SSRF 가드에 걸리지 않고
    // fetchAndCache()를 로컬 HttpServer 스텁(어쩔 수 없이 루프백 주소)으로 향하게 할 수 있다.
    // 가드 자체는 isInternalAddress(...)와 순수 루프백 URL을 이용해 별도로 검증한다.
    AnimalImageCacheService(String cacheDir, Predicate<InetAddress> disallowedHost) {
        this.cacheDir = Path.of(cacheDir);
        this.disallowedHost = disallowedHost;
        try {
            Files.createDirectories(this.cacheDir);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot create image cache dir " + this.cacheDir, ex);
        }
    }

    public Optional<CachedImage> read(long imageId, String sourceUrl) {
        String key = cacheKey(imageId, sourceUrl);
        for (var entry : EXTENSION_BY_CONTENT_TYPE.entrySet()) {
            Path file = cacheDir.resolve(key + "." + entry.getValue());
            if (Files.exists(file)) return Optional.of(new CachedImage(readBytes(file), entry.getKey()));
        }
        Path fallback = cacheDir.resolve(key + ".bin");
        Path meta = cacheDir.resolve(key + ".meta");
        if (Files.exists(fallback) && Files.exists(meta)) {
            return Optional.of(new CachedImage(readBytes(fallback), readMeta(meta)));
        }
        return Optional.empty();
    }

    public CachedImage fetchAndCache(long imageId, String sourceUrl) {
        HttpResponse<InputStream> response = send(URI.create(sourceUrl), imageId, false);
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200)
                throw new IllegalStateException("Image source returned HTTP " + response.statusCode() + " for " + imageId);
            String contentType = response.headers().firstValue("Content-Type")
                    .map(v -> v.split(";", 2)[0].strip().toLowerCase(Locale.ROOT)).orElse("");
            boolean octetStream = "application/octet-stream".equals(contentType);
            if (!contentType.startsWith("image/") && !octetStream)
                throw new IllegalStateException("Image source returned non-image Content-Type " + contentType + " for " + imageId);

            byte[] bytes = readUpToLimit(body, imageId);
            if (octetStream) {
                contentType = sniffImageContentType(bytes);
                if (contentType == null)
                    throw new IllegalStateException("Image source returned application/octet-stream without a supported image signature for " + imageId);
            }
            writeCacheFile(cacheKey(imageId, sourceUrl), contentType, bytes);
            return new CachedImage(bytes, contentType);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed closing image response body for " + imageId, ex);
        }
    }

    // 요청을 보낸다. 리다이렉트(java.net.http.HttpClient의 기본값은 Redirect.NEVER)를 만나면
    // 정확히 1홉만 따라가며 그 리다이렉트 대상에도 SSRF 호스트 가드를 다시 적용한다 —
    // HttpClient 내장 NORMAL 정책으로 자동 추종하면 홉마다 가드를 재검사하지 않아, 공개 호스트가
    // 내부 주소로 바로 리다이렉트하는 것을 막지 못한다.
    private HttpResponse<InputStream> send(URI uri, long imageId, boolean isRedirectHop) {
        rejectInternalHost(uri.getHost());
        HttpResponse<InputStream> response;
        try {
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).GET().build();
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Image fetch interrupted for " + imageId, ex);
        } catch (IOException ex) {
            throw new IllegalStateException("Image fetch failed for " + imageId, ex);
        }
        int status = response.statusCode();
        if (!isRedirectHop && (status == 301 || status == 302 || status == 303 || status == 307 || status == 308)) {
            String location = response.headers().firstValue("Location")
                    .orElseThrow(() -> new IllegalStateException("Redirect without Location for " + imageId));
            try {
                response.body().close();
            } catch (IOException ignored) {
                // 최선 노력: 어차피 커넥션은 폐기되는 중이다
            }
            return send(uri.resolve(location), imageId, true);
        }
        return response;
    }

    // 호스트를 미리 resolve해 루프백/사설망/링크-로컬/any-local 대상을 거부한다 — 원래 브라우저가
    // 직접 하던 이 서버 측 fetch가 내부망 주소를 향하지 못하게 하기 위해서다. 호스트가 공인
    // 주소와 사설 주소 레코드를 동시에 광고할 수 있으므로 resolve된 모든 주소를 검사한다(첫
    // 번째만 검사하지 않는다). 이 JDK의 java.net.http.HttpClient는 요청 단위 DNS 리졸버
    // 오버라이드를 제공하지 않아 실제 커넥션은 호스트명을 다시 resolve한다 — 이 검사와 실제 연결
    // 사이의 좁은 DNS 리바인딩 틈은 이 메서드가 막지 못하는, 감수하기로 한 잔여 위험이다(설계
    // 문서의 SSRF 절 참고).
    private void rejectInternalHost(String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new IllegalStateException("Cannot resolve image host " + host, ex);
        }
        for (InetAddress address : addresses) {
            if (disallowedHost.test(address))
                throw new IllegalStateException("Refusing to fetch image from internal address " + address);
        }
    }

    static boolean isInternalAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isAnyLocalAddress() || address.isMulticastAddress())
            return true;
        // Inet6Address.isSiteLocalAddress()는 폐기된 fec0::/10 대역만 인식한다. 현대적인 IPv6
        // 유니크 로컬 주소(fc00::/7)는 이 검사를 완전히 통과해버린다.
        if (address instanceof Inet6Address v6) {
            byte first = v6.getAddress()[0];
            return (first & 0xFE) == 0xFC;
        }
        return false;
    }

    private byte[] readUpToLimit(InputStream in, long imageId) {
        try {
            byte[] bytes = in.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES)
                throw new IllegalStateException("Image exceeds " + MAX_BYTES + " byte cache limit for " + imageId);
            return bytes;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed reading image body for " + imageId, ex);
        }
    }

    private static String sniffImageContentType(byte[] bytes) {
        if (hasBytes(bytes, 0, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) return "image/jpeg";
        if (hasBytes(bytes, 0, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) return "image/png";
        if (hasBytes(bytes, 0, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || hasBytes(bytes, 0, "GIF89a".getBytes(StandardCharsets.US_ASCII))) return "image/gif";
        if (hasBytes(bytes, 0, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && hasBytes(bytes, 8, "WEBP".getBytes(StandardCharsets.US_ASCII))) return "image/webp";
        return null;
    }

    private static boolean hasBytes(byte[] bytes, int offset, byte[] expected) {
        if (bytes.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++)
            if (bytes[offset + i] != expected[i]) return false;
        return true;
    }

    private void writeCacheFile(String key, String contentType, byte[] bytes) {
        String extension = EXTENSION_BY_CONTENT_TYPE.get(contentType);
        String baseName = key + "." + (extension != null ? extension : "bin");
        Path target = cacheDir.resolve(baseName);
        // 키별이 아니라 쓰기별로 유일하게 만든다: 설계상 같은 키에 대한 동시 캐시 미스가
        // 여기까지 함께 도달할 수 있는데, "{key}.tmp"라는 공유 경로를 쓰면 둘 중 하나가
        // rename하기 전에 두 쓰기 작업이 같은 임시 파일에 뒤섞여 쓰일 수 있다.
        Path tmp = cacheDir.resolve(baseName + "." + java.util.UUID.randomUUID() + ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            // 이번 fetch가 자신의 .meta를 쓰기 *전에* 오래된 형제 변형 파일(예: 이전에 .bin
            // 폴백 경로를 탔던 fetch가 남긴 .meta)을 먼저 정리한다 — 순서를 바꾸면 아래 정리
            // 로직이 방금 이 호출이 쓴 .meta를 `keep`도 아니고 .tmp 파일도 아니라는 이유로
            // 지워버려, 이 키의 .bin+.meta 폴백이 영구히 망가진다.
            removeStaleVariants(key, target);
            if (extension == null) Files.writeString(cacheDir.resolve(key + ".meta"), contentType);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed writing image cache file for " + key, ex);
        }
    }

    // 같은 키에 대한 이전 fetch가 다른 확장자로 캐시했다면(호출 사이에 소스가 Content-Type을
    // 바꾼 경우) 그 파일을 지워 read()의 확장자 탐색이 낡은 바이트를 반환하지 않게 한다.
    private void removeStaleVariants(String key, Path keep) {
        String prefix = key + ".";
        try (DirectoryStream<Path> siblings = Files.newDirectoryStream(cacheDir, prefix + "*")) {
            for (Path sibling : siblings) {
                if (!sibling.equals(keep) && !sibling.getFileName().toString().endsWith(".tmp")) {
                    Files.deleteIfExists(sibling);
                }
            }
        } catch (IOException ex) {
            log.warn("Failed cleaning up stale image cache variants for {}: {}", key, ex.getMessage());
        }
    }

    private byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed reading cached image file " + file, ex);
        }
    }

    private String readMeta(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed reading cached image meta " + file, ex);
        }
    }

    // 컨트롤러가 캐시 파일을 저장/조회할 때 쓰는 것과 같은 키로 콘텐츠 기반 ETag를 만들 수
    // 있도록 공개한다 — id만으로 ETag를 만들면 id 재사용이나 소스 측 콘텐츠 변경으로 캐시된
    // 바이트가 바뀐 뒤에도 최대 7일간 304를 계속 내려주게 된다.
    public static String cacheKey(long imageId, String sourceUrl) {
        return imageId + "-" + hash8(sourceUrl);
    }

    private static String hash8(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
