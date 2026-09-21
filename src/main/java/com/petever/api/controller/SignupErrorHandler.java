package com.petever.api.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.petever.api.service.DuplicateEmailException;
import com.petever.api.service.SignupValidationException;

@RestControllerAdvice(assignableTypes = SignupController.class)
class SignupErrorHandler {
    @ExceptionHandler(SignupValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    SignupError invalid(SignupValidationException error) {
        return new SignupError("INVALID_INPUT", "입력값을 확인해 주세요.", error.fieldErrors());
    }

    @ExceptionHandler(DuplicateEmailException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    SignupError duplicateEmail() {
        return new SignupError("DUPLICATE_EMAIL", "이미 가입된 이메일입니다.", Map.of());
    }

    record SignupError(String code, String message, Map<String, String> fieldErrors) {}
}
