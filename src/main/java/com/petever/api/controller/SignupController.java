package com.petever.api.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.petever.api.entity.User;
import com.petever.api.service.UserSignupService;

@RestController
@Profile("supabase")
@RequestMapping("/api/auth")
public class SignupController {
    private final UserSignupService signups;

    public SignupController(UserSignupService signups) {
        this.signups = signups;
    }

    @PostMapping(value = "/signup", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    SignupResponse signup(@RequestBody SignupRequest request) {
        User user = signups.signup(request.email, request.password, request.passwordConfirmation,
                request.nickname, request.phone);
        return new SignupResponse(user.id, user.email, user.nickname);
    }

    public static final class SignupRequest {
        public String email;
        public String password;
        public String passwordConfirmation;
        public String nickname;
        public String phone;
    }

    public record SignupResponse(Long id, String email, String nickname) {}
}
