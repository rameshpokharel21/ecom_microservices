package com.ramesh.user.exceptions;

//Carries the Keycloak response BODY, which is where the actual reason lives.
public class KeycloakAdminException extends RuntimeException {
    public KeycloakAdminException(String message) {
        super(message);
    }

    public KeycloakAdminException(String message, Throwable cause) {
        super(message, cause);
    }
}
