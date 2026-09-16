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
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

// Caches animal_images.url (an external public-API file) on local disk, keyed by
// animal_images.id + a short hash of the source URL, so the browser never talks to the
// (slow, uncached) external host directly. The URL hash in the key -- not just the id -- guards
// against a dev-environment schema reset restarting the id sequence and silently serving a stale
// file for a reused id (a real risk for the local-dev speedup this exists for). Disk cache is not
// persisted across redeploys and is not shared across instances -- acceptable for now since the
// hosting target is still undecided (see docs/architecture.md); see the design doc for the
// known-limitations list this trades off against.
@Service
@Profile("supabase")
public class AnimalImageCacheService {
    private static final Logger log = LoggerFactory.getLogger(AnimalImageCacheService.class);
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    // LinkedHashMap: iteration order (used by read() to probe candidate extensions) must be
    // deterministic across JVMs, unlike Map.of(...).
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

    // Visible for tests: lets AnimalImageCacheServiceTests point fetchAndCache() at a local
    // HttpServer stub (necessarily loopback) without tripping the real SSRF guard, while the
    // guard itself is exercised separately via isInternalAddress(...) and a plain-loopback URL.
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
                    .map(v -> v.split(";", 2)[0].strip()).orElse("");
            if (!contentType.startsWith("image/"))
                throw new IllegalStateException("Image source returned non-image Content-Type " + contentType + " for " + imageId);

            byte[] bytes = readUpToLimit(body, imageId);
            writeCacheFile(cacheKey(imageId, sourceUrl), contentType, bytes);
            return new CachedImage(bytes, contentType);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed closing image response body for " + imageId, ex);
        }
    }

    // Sends the request; on a redirect (java.net.http.HttpClient defaults to Redirect.NEVER)
    // follows exactly one hop, re-running the SSRF host guard against the redirect target --
    // auto-following (HttpClient's built-in NORMAL policy) would not re-check the guard per hop,
    // which would let a public host redirect straight to an internal address.
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
                // best-effort: the connection is being discarded either way
            }
            return send(uri.resolve(location), imageId, true);
        }
        return response;
    }

    // Resolves the host up front and rejects loopback/private/link-local/any-local targets so this
    // server-side fetch (standing in for what the browser used to do directly) can't be pointed at
    // internal network addresses. Checks every resolved address (not just the first) since a host
    // can advertise both a public and a private A/AAAA record. java.net.http.HttpClient has no
    // per-request DNS resolver override in this JDK, so the actual connection re-resolves the host
    // name -- a narrow DNS-rebinding gap between this check and that connection is an accepted
    // residual risk here (see the design doc's SSRF section) rather than something this method closes.
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
        // Inet6Address.isSiteLocalAddress() only recognizes the deprecated fec0::/10 range; modern
        // IPv6 unique local addresses (fc00::/7) fall through it entirely.
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

    private void writeCacheFile(String key, String contentType, byte[] bytes) {
        String extension = EXTENSION_BY_CONTENT_TYPE.get(contentType);
        String baseName = key + "." + (extension != null ? extension : "bin");
        Path target = cacheDir.resolve(baseName);
        // Unique per write (not just per key): the design allows concurrent misses for the same
        // key to both reach here, and a shared "{key}.tmp" path would let two writers interleave
        // into the same temp file before either renamed it.
        Path tmp = cacheDir.resolve(baseName + "." + java.util.UUID.randomUUID() + ".tmp");
        try {
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            // Clean up stale sibling variants (e.g. a leftover .meta from a previous fetch that
            // took the .bin fallback path) *before* writing this fetch's own .meta -- otherwise the
            // cleanup below would delete the .meta this call just wrote, since it's neither `keep`
            // nor a .tmp file, permanently breaking the .bin+.meta fallback for this key.
            removeStaleVariants(key, target);
            if (extension == null) Files.writeString(cacheDir.resolve(key + ".meta"), contentType);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed writing image cache file for " + key, ex);
        }
    }

    // If an earlier fetch for the same key cached a different extension (the source flipped
    // Content-Type between calls), drop it so read()'s extension probe can't return stale bytes.
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

    // Public so the controller can derive a content-addressed ETag from the same key used to
    // store/look up the cache file, instead of an id-only ETag that would keep serving a 304 for
    // up to 7 days after id-reuse or a source-side content change replaces the cached bytes.
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
