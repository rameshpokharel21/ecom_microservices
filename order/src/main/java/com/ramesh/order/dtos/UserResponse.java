package com.ramesh.order.dtos;



import com.ramesh.order.entities.UserRole;
import lombok.Data;

@Data
public class UserResponse {

    private String id;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private UserRole role;
    //Must match user-service's JSON key or Jackson leaves it null - renamed with it.
    private AddressDto address;



}
