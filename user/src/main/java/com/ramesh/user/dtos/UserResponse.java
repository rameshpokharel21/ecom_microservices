package com.ramesh.user.dtos;


import lombok.Data;

@Data
public class UserResponse {

    //The Keycloak user id / JWT "sub" - see User.id.
    private String id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    //No role here either - a client that wants it reads realm_access.roles from its own
    //token, which cannot go stale the way a stored copy did.
    private AddressDto addressDto;



}
