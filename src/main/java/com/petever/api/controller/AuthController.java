package com.petever.api.controller;

import java.io.Serializable;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.petever.api.entity.User;
import com.petever.api.service.InvalidCredentialsException;
import com.petever.api.service.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("supabase")
@RequestMapping("/api/auth")
public class AuthController {
    private final LoginService loginService;
    private final SecurityContextRepository contexts;
    private final CsrfTokenRepository csrfTokens;

    public AuthController(LoginService loginService, SecurityContextRepository contexts,
            CsrfTokenRepository csrfTokens) {
        this.loginService = loginService;
        this.contexts = contexts;
        this.csrfTokens = csrfTokens;
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken());
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    Account login(@RequestBody LoginRequest request, HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        User user = loginService.authenticate(request.email, request.password);
        Account account = new Account(user.id, user.email, user.nickname);
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                account, null, List.of(new SimpleGrantedAuthority(securityRole(user.systemRole))));
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        servletRequest.getSession(true);
        servletRequest.changeSessionId();
        contexts.saveContext(context, servletRequest, servletResponse);
        csrfTokens.saveToken(null, servletRequest, servletResponse);
        return account;
    }

    private static String securityRole(String systemRole) {
        return "ADMIN".equals(systemRole) ? "ROLE_ADMIN" : "ROLE_USER";
    }

    @GetMapping("/session")
    Account session(Authentication authentication) {
        return (Account) authentication.getPrincipal();
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(HttpServletRequest request, HttpServletResponse response) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
        response.addHeader("Set-Cookie", ResponseCookie.from("JSESSIONID", "")
                .path("/").httpOnly(true).maxAge(Duration.ZERO).sameSite("Lax").build().toString());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    Map<String, Object> invalidCredentials() {
        return Map.of("code", "INVALID_CREDENTIALS",
                "message", "이메일 또는 비밀번호가 올바르지 않습니다.", "fieldErrors", Map.of());
    }

    public static final class LoginRequest {
        public String email;
        public String password;
    }

    public record Account(Long id, String email, String nickname) implements Serializable {}
}
