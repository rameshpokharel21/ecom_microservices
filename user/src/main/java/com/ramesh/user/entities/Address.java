package com.ramesh.user.entities;

import lombok.Data;

@Data

public class Address {
    private Long id;

    private String street;

    private String city;

    private String state;

    private String zipcode;

    private String country;

}
