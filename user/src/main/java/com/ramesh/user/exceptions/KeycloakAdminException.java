package com.ramesh.user.exceptions;

import org.springframework.http.HttpStatusCode;

//Carries the Keycloak status and its parsed reason: without the status the handler cannot
//tell "Keycloak is down" from "Keycloak rejected this input", and answered 502 to both.
public class KeycloakAdminException extends RuntimeException {

    private final HttpStatusCode status;
    private final String reason;

    public KeycloakAdminException(String message) {
        this(null, null, message, null);
    }

    public KeycloakAdminException(String message, Throwable cause) {
        this(null, null, message, cause);
    }

    public KeycloakAdminException(HttpStatusCode status, String reason, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.reason = reason;
    }

    /** Keycloak's own status, or null when the call never produced a response. */
    public HttpStatusCode getStatus() {
        return status;
    }

    /** The safe, human-readable half of the body - no URLs, fit to show a caller. */
    public String getReason() {
        return reason;
    }
}
