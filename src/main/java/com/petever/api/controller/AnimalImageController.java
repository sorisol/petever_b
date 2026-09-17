package com.petever.api.controller;

import com.petever.api.entity.AnimalImage;
import com.petever.api.repository.AnimalImageRepository;
import com.petever.api.service.AnimalImageCacheService;
import com.petever.api.service.AnimalImageCacheService.CachedImage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.context.annotation.Profile;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

// 브라우저가 직접 두드리던 외부 공공 API 호스트 대신 우리 서버를 통해 동물 사진을 서빙한다
// (docs/superpowers/specs/2026-09-15-animal-image-proxy-cache-design.md 참고). 일반 <img>
// 요청은 인증 정보를 싣지 않으므로 익명으로도 계속 접근 가능해야 한다(SecurityConfig가
// GET /api/animals/images/*를 허용).
@RestController
@Profile("supabase")
@RequestMapping("/api/animals/images")
public class AnimalImageController {
    private static final Logger log = LoggerFactory.getLogger(AnimalImageController.class);

    private final AnimalImageRepository images;
    private final AnimalImageCacheService cache;

    public AnimalImageController(AnimalImageRepository images, AnimalImageCacheService cache) {
        this.images = images;
        this.cache = cache;
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> get(@PathVariable long id) {
        AnimalImage image = images.findByIdAndAnimalVisibility(id, "PUBLIC").orElse(null);
        if (image == null) return ResponseEntity.notFound().build();

        CachedImage cached;
        try {
            // read()도 예외를 던질 수 있다(예: 동시에 진행 중인 캐시 정리가 Files.exists와
            // Files.readAllBytes 사이에 파일을 지우는 경우) -- fetchAndCache와 동일하게
            // 실패 처리해야 한다. 이 엔드포인트가 호출자에게 약속한 "실패 = 404" 계약을 깨고
            // 500이 새어 나가면 안 된다.
            cached = cache.read(id, image.url).orElseGet(() -> cache.fetchAndCache(id, image.url));
        } catch (RuntimeException ex) {
            log.warn("Failed to fetch/cache animal image {}: {}", id, ex.getMessage());
            return ResponseEntity.notFound().build();
        }

        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(cached.contentType());
        } catch (InvalidMediaTypeException ex) {
            contentType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                // id 단독이 아니라 콘텐츠 기반(id + 원본 URL 해시)으로 만든다: id만 쓰면
                // id 재사용(dev 스키마 리셋) 이후나 소스 측 콘텐츠 변경으로 같은 id의 바이트가
                // 바뀐 뒤에도 최대 7일간 계속 304를 내려주게 된다.
                .eTag("\"img-" + AnimalImageCacheService.cacheKey(id, image.url) + "\"")
                .body(cached.bytes());
    }
}
