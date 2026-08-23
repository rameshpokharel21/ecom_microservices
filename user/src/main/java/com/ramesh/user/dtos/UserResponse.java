package com.ramesh.user.dtos;


import com.ramesh.user.entities.UserRole;
import lombok.Data;

@Data
public class UserResponse {

    //The Keycloak user id / JWT "sub" - see User.id.
    private String id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private UserRole role;
    private AddressDto addressDto;



}
