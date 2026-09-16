package com.petever.api;

import com.petever.api.controller.*;
import com.petever.api.entity.*;
import com.petever.api.repository.*;
import com.petever.api.service.*;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class AnimalSourceParserTests {
    private final AnimalSourceParser parser = new AnimalSourceParser();

    @Test
    void acceptsSingletonAndArrayItems() {
        var single = """
            {"response":{"header":{"resultCode":"00"},"body":{"totalCount":1,"items":{"item":{"desertionNo":"A1","kindCd":"[개] 믹스견"}}}}}
            """;
        assertEquals("A1", parser.parse(single).items().getFirst().path("desertionNo").asText());
        var array = """
            {"response":{"header":{"resultCode":"00"},"body":{"totalCount":2,"items":{"item":[{"desertionNo":"A1"},{"desertionNo":"A2"}]}}}}
            """;
        assertEquals(2, parser.parse(array).items().size());
    }

    @Test
    void rejectsUpstreamError() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("""
            {"response":{"header":{"resultCode":"30","resultMsg":"SERVICE KEY IS NOT REGISTERED"}}}
            """));
    }
}
