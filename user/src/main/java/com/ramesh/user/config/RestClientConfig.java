package com.ramesh.user.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;

@Configuration
public class RestClientConfig {

    //Its own bean rather than the shared one: throw-on-4xx is right for the Keycloak
    //admin API and wrong to impose on every other caller.
    @Bean("keycloakRestClient")
    public RestClient keycloakRestClient(ObjectMapper objectMapper) {
        return RestClient.builder()
                .requestFactory(getClientHttpRequestFactory())
                //The BODY is the diagnosis. Keycloak answers 409 with
                //{"errorMessage":"User exists with same username"}; statusText alone
                //reduced that to "Client error: Conflict".
                .defaultStatusHandler(HttpStatusCode::isError,
                        (request, response) -> {
                            String body = readBody(response.getBody());
                            //Status is kept as a status, not baked into the string: the
                            //handler needs it to distinguish a rejection from an outage.
                            throw new KeycloakAdminException(
                                    response.getStatusCode(),
                                    extractReason(objectMapper, body, response.getStatusCode()),
                                    "Keycloak " + request.getMethod() + " " + request.getURI()
                                            + " -> " + response.getStatusCode() + " " + body,
                                    null);
                        })
                .build();
    }

    //Keycloak names its message errorMessage on the Admin API and error_description on the
    //token endpoint; both are safe to show a caller, unlike the URL in the log message.
    private static String extractReason(ObjectMapper objectMapper, String body, HttpStatusCode status) {
        try {
            JsonNode root = objectMapper.readTree(body);
            for (String key : List.of("errorMessage", "error_description", "error")) {
                JsonNode value = root.get(key);
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    return value.asText();
                }
            }
        } catch (Exception ignored) {
            //A non-JSON body (an HTML error page, an empty 404) is not worth failing over.
        }
        return "Keycloak rejected the request (" + status + ")";
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
