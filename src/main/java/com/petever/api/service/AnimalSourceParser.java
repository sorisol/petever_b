package com.petever.api.service;



import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class AnimalSourceParser {
    public record Page(List<JsonNode> items, int totalCount) {}
    private final ObjectMapper mapper = new ObjectMapper();

    public Page parse(String json) {
        JsonNode root = mapper.readTree(json).path("response");
        String code = root.path("header").path("resultCode").asText();
        if (!"00".equals(code)) throw new IllegalArgumentException("Public API error code: " + code);
        JsonNode body = root.path("body");
        JsonNode item = body.path("items").path("item");
        List<JsonNode> items = new ArrayList<>();
        if (item.isArray()) item.forEach(items::add);
        else if (item.isObject()) items.add(item);
        int total = body.path("totalCount").asInt();
        if (total > 0 && items.isEmpty()) throw new IllegalArgumentException("Public API response has no items");
        return new Page(items, total);
    }
}
