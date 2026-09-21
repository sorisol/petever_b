package com.petever.api.service;

import java.util.Map;

public class SignupValidationException extends RuntimeException {
    private final Map<String, String> fieldErrors;

    public SignupValidationException(Map<String, String> fieldErrors) {
        super("Invalid signup input");
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
