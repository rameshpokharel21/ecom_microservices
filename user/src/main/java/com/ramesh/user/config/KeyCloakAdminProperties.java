package com.ramesh.user.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

//Bound from keycloak.admin.* in config/user-service.yml. clientId/clientSecret are a
//SERVICE ACCOUNT in the ecom-app realm, not the master realm's admin user.
@Component
@ConfigurationProperties(prefix = "keycloak.admin")
@Getter
@Setter
public class KeyCloakAdminProperties {
    private String serverUrl;
    private String realm;
    private String clientId;
    private String clientSecret;

    public String tokenUrl() {
        return serverUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    public String usersUrl() {
        return serverUrl + "/admin/realms/" + realm + "/users";
    }

    public String realmRoleUrl(String roleName) {
        return serverUrl + "/admin/realms/" + realm + "/roles/" + roleName;
    }

    public String realmRoleMappingUrl(String userId) {
        return usersUrl() + "/" + userId + "/role-mappings/realm";
    }
}
