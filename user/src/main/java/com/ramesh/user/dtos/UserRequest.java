package com.ramesh.user.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class UserRequest {

    //WRITE_ONLY, not @JsonIgnore: @JsonIgnore blocks DESERIALIZATION too, so the
    //password never arrived and Keycloak got credentials:[{value:null}].
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
    private String username;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private AddressDto addressDto;
}
