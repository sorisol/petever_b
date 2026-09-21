package com.petever.api.service;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException() {
        super("Duplicate email");
    }
}
