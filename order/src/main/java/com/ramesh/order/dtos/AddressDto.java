package com.ramesh.order.dtos;

import lombok.Data;

@Data
public class AddressDto {

    private String street;

    private String city;

    private String state;

    private String zipcode;

    private String country;
}
