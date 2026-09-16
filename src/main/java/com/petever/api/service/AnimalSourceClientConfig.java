package com.petever.api.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Two independent public API sources exist (rescue-animal notices vs. loss reports); each gets
// its own AnimalSourceClient bean so AnimalSyncService/AnimalInitialSync can sync both explicitly
// instead of inferring which source is configured from a single client.
@Configuration
public class AnimalSourceClientConfig {

    // Endpoint path unverified against a live response (no service-key access in this session);
    // matches the operation name pattern documented for data.go.kr group 1543061. Confirm and
    // adjust via ANIMAL_API_ABANDONMENT_URL before relying on this default in production.
    // Key priority: ANIMAL_API_SERVICE_KEY first, LOSSINFO_API_KEY as fallback (see .env.example).
    @Bean
    public AnimalSourceClient abandonmentSourceClient(
            @Value("${ANIMAL_API_ABANDONMENT_URL:https://apis.data.go.kr/1543061/abandonmentPublicService_v2/abandonmentPublic_v2}") String url,
            @Value("${ANIMAL_API_SERVICE_KEY:${LOSSINFO_API_KEY:}}") String serviceKey) {
        return new AnimalSourceClient(url, serviceKey);
    }

    // Key priority: LOSSINFO_API_KEY first, ANIMAL_API_SERVICE_KEY as fallback (see .env.example).
    // ANIMAL_API_URL is the pre-split, single-source override this project used before two
    // sources existed; it pointed at this loss endpoint in practice, so it stays as this client's
    // fallback so an environment that only set the old variable keeps working unchanged.
    @Bean
    public AnimalSourceClient lossInfoSourceClient(
            @Value("${ANIMAL_API_LOSS_URL:${ANIMAL_API_URL:https://apis.data.go.kr/1543061/lossInfoService/lossInfo}}") String url,
            @Value("${LOSSINFO_API_KEY:${ANIMAL_API_SERVICE_KEY:}}") String serviceKey) {
        return new AnimalSourceClient(url, serviceKey);
    }
}
