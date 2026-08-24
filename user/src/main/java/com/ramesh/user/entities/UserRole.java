package com.ramesh.user.entities;

//Names the REALM roles as they exist in Keycloak - no ROLE_ prefix, because the gateway's
//converter adds it. Nothing stores these any more; CUSTOMER is what self-signup assigns
//and ADMIN is here to document the vocabulary the gateway's hasRole rules expect.
public enum UserRole {
    CUSTOMER, ADMIN
}
