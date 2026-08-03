package com.ramesh.user.dtos;

import lombok.Data;

@Data
public class UserRequest {

    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private AddressDto addressDto;
}
