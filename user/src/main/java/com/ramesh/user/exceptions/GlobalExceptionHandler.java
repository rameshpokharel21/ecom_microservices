package com.ramesh.user.exceptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;

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

    //A 4xx from Keycloak is the CALLER's fault - a duplicate email, a password the policy
    //rejects - so it is passed through as that same status. Only a real upstream failure
    //is a 502; before this everything was one, which told a browser to retry a request
    //that could never succeed.
    @ExceptionHandler(KeycloakAdminException.class)
    public ProblemDetail handleKeycloak(KeycloakAdminException e) {
        HttpStatusCode upstream = e.getStatus();

        if (upstream != null && upstream.is4xxClientError() && !isOurCredentials(upstream)) {
            logger.warn("Keycloak rejected the request: {}", e.getMessage());
            return ProblemDetail.forStatusAndDetail(upstream, e.getReason());
        }

        //The message holds the internal admin URL, so it stays in the log. Signup is the
        //one permitAll route - an anonymous caller must not learn the in-network topology.
        logger.error("Keycloak admin call failed", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "The identity provider could not be reached. Please try again.");
    }

    //401/403 mean OUR service-account credentials are wrong, not the caller's input.
    //Returning 401 would also trip the front end's interceptor and log the user out.
    private static boolean isOurCredentials(HttpStatusCode status) {
        return status.value() == 401 || status.value() == 403;
    }

    //Keycloak unreachable - DNS failure, refused connection, read timeout. The status
    //handler above never sees this: it only runs when there IS a response, so this used
    //to fall through to Spring's 500, i.e. "user-service is broken" for an outage that is
    //entirely upstream. Verified with `docker stop ecom_keycloak`.
    @ExceptionHandler(ResourceAccessException.class)
    public ProblemDetail handleKeycloakUnreachable(ResourceAccessException e) {
        logger.error("Keycloak unreachable", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY,
                "The identity provider could not be reached. Please try again.");
    }
}
