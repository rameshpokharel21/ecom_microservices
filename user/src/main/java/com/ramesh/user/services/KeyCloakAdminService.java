package com.ramesh.user.services;

import com.ramesh.user.config.KeyCloakAdminProperties;
import com.ramesh.user.dtos.UserRequest;
import com.ramesh.user.exceptions.KeycloakAdminException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every URL is built from {@link KeyCloakAdminProperties#getServerUrl()}. Nothing here
 * may hardcode localhost: inside the user-service container "localhost" is user-service
 * itself, the same trap as the Redis host, KAFKA_BROKERS, and the gateway's jwk-set-uri.
 *
 * <p>Roles are REALM roles, so no client UUID is needed anywhere - that is what removed
 * the old {@code client-uid: 7477 #fix this} property. The role name must exist in
 * Keycloak beforehand and must NOT carry a {@code ROLE_} prefix: the gateway's
 * converter prepends it, so a realm role named ROLE_CUSTOMER becomes the authority
 * ROLE_ROLE_CUSTOMER and matches nothing.
 */
@Service
public class KeyCloakAdminService {

    private static final Logger logger = LoggerFactory.getLogger(KeyCloakAdminService.class);

    private final KeyCloakAdminProperties adminProperties;
    private final RestClient restClient;

    public KeyCloakAdminService(KeyCloakAdminProperties adminProperties,
                                @Qualifier("keycloakRestClient") RestClient restClient) {
        this.adminProperties = adminProperties;
        this.restClient = restClient;
    }

    /**
     * client_credentials against a service account in the APP realm - not
     * grant_type=password against master. The master admin controls the whole Keycloak
     * installation and has no business being in a microservice's environment; this
     * client holds only manage-users and view-realm.
     */
    public String getAdminAccessToken() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "client_credentials");
        params.add("client_id", adminProperties.getClientId());
        params.add("client_secret", adminProperties.getClientSecret());

        ResponseEntity<Map> response = restClient
                .post()
                .uri(adminProperties.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(params)
                .retrieve()
                .toEntity(Map.class);

        Map<String, Object> body = response.getBody();
        String token = body == null ? null : (String) body.get("access_token");
        if (token == null) {
            throw new KeycloakAdminException("No access_token in Keycloak token response: " + body);
        }
        return token;
    }

    /** @return the Keycloak user id, which is the "sub" claim of every token it issues. */
    public String createUser(String token, UserRequest userRequest) {
        Map<String, Object> credential = new HashMap<>();
        credential.put("type", "password");
        credential.put("value", userRequest.getPassword());
        credential.put("temporary", false);

        Map<String, Object> userPayload = new HashMap<>();
        userPayload.put("username", userRequest.getUsername());
        userPayload.put("email", userRequest.getEmail());
        userPayload.put("enabled", true);
        userPayload.put("firstName", userRequest.getFirstName());
        userPayload.put("lastName", userRequest.getLastName());
        userPayload.put("credentials", List.of(credential));

        ResponseEntity<Void> response = restClient
                .post()
                .uri(adminProperties.usersUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .headers(auth -> auth.setBearerAuth(token))
                .body(userPayload)
                .retrieve()
                .toBodilessEntity();

        //201 carries no body; the new id is only in the Location header.
        URI location = response.getHeaders().getLocation();
        if (location == null) {
            throw new KeycloakAdminException("Keycloak returned no Location header for the new user");
        }
        String path = location.getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    public void assignRealmRoleToUser(String token, String userId, String roleName) {
        Map<String, Object> roleRep = getRealmRoleRepresentation(token, roleName);

        restClient.post()
                .uri(adminProperties.realmRoleMappingUrl(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .headers(auth -> auth.setBearerAuth(token))
                //A LIST. This endpoint takes List<RoleRepresentation>; a bare object is a 400.
                .body(List.of(roleRep))
                .retrieve()
                .toBodilessEntity();

        logger.info("Assigned realm role {} to Keycloak user {}", roleName, userId);
    }

    /** Compensating action for a failed local save - see UserService.addUser. */
    public void deleteUser(String token, String userId) {
        restClient.delete()
                .uri(adminProperties.usersUrl() + "/" + userId)
                .headers(auth -> auth.setBearerAuth(token))
                .retrieve()
                .toBodilessEntity();

        logger.warn("Deleted Keycloak user {} after a failed local save", userId);
    }

    //404s if the realm role does not exist yet - create CUSTOMER and ADMIN in Keycloak.
    private Map<String, Object> getRealmRoleRepresentation(String token, String roleName) {
        ResponseEntity<Map> response = restClient.get()
                .uri(adminProperties.realmRoleUrl(roleName))
                .headers(auth -> auth.setBearerAuth(token))
                .retrieve()
                .toEntity(Map.class);
        return response.getBody();
    }
}
