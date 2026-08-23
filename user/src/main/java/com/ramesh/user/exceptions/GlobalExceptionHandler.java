package com.ramesh.user.exceptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    //The unique index on email. Without this the caller sees a bare 500 and cannot tell
    //a duplicate signup from a broken service.
    @ExceptionHandler(DuplicateKeyException.class)
    public ProblemDetail handleDuplicate(DuplicateKeyException e) {
        logger.warn("Duplicate key on user create: {}", e.getMessage());
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "A user with that email already exists");
    }

    //502, not 500: the failure is in Keycloak, not in this service.
    @ExceptionHandler(KeycloakAdminException.class)
    public ProblemDetail handleKeycloak(KeycloakAdminException e) {
        logger.error("Keycloak admin call failed", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }
}
