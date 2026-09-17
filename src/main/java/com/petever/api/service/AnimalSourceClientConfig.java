package com.petever.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 서로 독립된 두 공공 API 소스(구조동물 공고 vs 분실 신고)가 있어, 하나의 클라이언트로
// 어떤 소스가 설정됐는지 추론하는 대신 AnimalSyncService/AnimalInitialSync가 둘 다 명시적으로
// 동기화할 수 있도록 각각 자기 AnimalSourceClient 빈을 갖는다.
@Configuration
public class AnimalSourceClientConfig {

    // 엔드포인트 경로는 실제 응답으로 검증되지 않았다(이 세션에는 서비스키 접근 권한이 없음) —
    // data.go.kr 1543061 그룹에 문서화된 오퍼레이션명 패턴과 일치시킨 것이다. 프로덕션에서
    // 이 기본값에 의존하기 전에 ANIMAL_API_ABANDONMENT_URL로 확인·조정할 것.
    // 키 우선순위: ANIMAL_API_SERVICE_KEY 우선, LOSSINFO_API_KEY는 폴백(.env.example 참고).
    @Bean
    public AnimalSourceClient abandonmentSourceClient(
            @Value("${ANIMAL_API_ABANDONMENT_URL:https://apis.data.go.kr/1543061/abandonmentPublicService_v2/abandonmentPublic_v2}") String url,
            @Value("${ANIMAL_API_SERVICE_KEY:${LOSSINFO_API_KEY:}}") String serviceKey) {
        return new AnimalSourceClient(url, serviceKey);
    }

    // 키 우선순위: LOSSINFO_API_KEY 우선, ANIMAL_API_SERVICE_KEY는 폴백(.env.example 참고).
    // ANIMAL_API_URL은 두 소스가 나뉘기 전 이 프로젝트가 쓰던, 소스 분리 이전의 단일 소스
    // 오버라이드 변수다 — 실제로는 이 분실 신고 엔드포인트를 가리켰으므로, 예전 변수만
    // 설정된 환경도 그대로 동작하도록 이 클라이언트의 폴백으로 남겨둔다.
    @Bean
    public AnimalSourceClient lossInfoSourceClient(
            @Value("${ANIMAL_API_LOSS_URL:${ANIMAL_API_URL:https://apis.data.go.kr/1543061/lossInfoService/lossInfo}}") String url,
            @Value("${LOSSINFO_API_KEY:${ANIMAL_API_SERVICE_KEY:}}") String serviceKey) {
        return new AnimalSourceClient(url, serviceKey);
    }
}
