package com.petever.api.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AnimalSyncDiagnosticsTests {
    @Test
    void reportsSqlStateWithoutLeakingExceptionMessage() {
        var error = new IllegalStateException("top secret", new SQLException("row has private value", "23502"));
        String diagnostic = AnimalSyncService.safeDiagnostic(error);
        assertTrue(diagnostic.contains("IllegalStateException"));
        assertTrue(diagnostic.contains("23502"));
        assertFalse(diagnostic.contains("top secret"));
        assertFalse(diagnostic.contains("private value"));
    }

    @Test
    void handlesNonSqlFailureWithoutMessage() {
        String diagnostic = AnimalSyncService.safeDiagnostic(new IllegalArgumentException("secret key"));
        assertTrue(diagnostic.contains("IllegalArgumentException"));
        assertFalse(diagnostic.contains("secret key"));
    }
    @Test
    void missingExternalIdReportsOnlyFieldNames() {
        var item = new ObjectMapper().readTree("{\"foo\":\"private value\",\"bar\":123}");
        String diagnostic = AnimalSyncService.safeDiagnostic(
                new IllegalArgumentException("Missing desertionNo"), item);
        assertTrue(diagnostic.contains("MISSING_DESERTION_NO"));
        assertTrue(diagnostic.contains("fields=bar,foo"));
        assertFalse(diagnostic.contains("private value"));
    }

    @Test
    void unrelatedIllegalArgumentOmitsMessageAndFields() {
        var item = new ObjectMapper().readTree("{\"foo\":\"private value\"}");
        String diagnostic = AnimalSyncService.safeDiagnostic(
                new IllegalArgumentException("secret value"), item);
        assertTrue(diagnostic.contains("IllegalArgumentException"));
        assertFalse(diagnostic.contains("fields="));
        assertFalse(diagnostic.contains("secret value"));
        assertFalse(diagnostic.contains("private value"));
    }
}