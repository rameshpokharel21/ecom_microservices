package com.ramesh.user.dtos;


import com.ramesh.user.entities.AddressDto;
import com.ramesh.user.entities.UserRole;
import lombok.Data;

@Data
public class UserResponse {

    private String id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private UserRole role;
    private AddressDto addressDto;



}
