package com.ramesh.user.config;

import com.ramesh.user.exceptions.KeycloakAdminException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Configuration
public class RestClientConfig {

    //Its own bean rather than the shared one: throw-on-4xx is right for the Keycloak
    //admin API and wrong to impose on every other caller.
    @Bean("keycloakRestClient")
    public RestClient keycloakRestClient() {
        return RestClient.builder()
                .requestFactory(getClientHttpRequestFactory())
                //The BODY is the diagnosis. Keycloak answers 409 with
                //{"errorMessage":"User exists with same username"}; statusText alone
                //reduced that to "Client error: Conflict".
                .defaultStatusHandler(HttpStatusCode::isError,
                        (request, response) -> {
                            throw new KeycloakAdminException(
                                    "Keycloak " + request.getMethod() + " " + request.getURI()
                                            + " -> " + response.getStatusCode() + " "
                                            + readBody(response.getBody()));
                        })
                .build();
    }

    private static String readBody(InputStream body) {
        try {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<body unreadable: " + e.getMessage() + ">";
        }
    }

    private ClientHttpRequestFactory getClientHttpRequestFactory() {
        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory();
        factory.setConnectionRequestTimeout(5000);
        factory.setReadTimeout(5000);
        return factory;
    }
}
