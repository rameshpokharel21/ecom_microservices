package com.ramesh.user.mappers;


import com.ramesh.user.dtos.UserRequest;
import com.ramesh.user.dtos.UserResponse;

import com.ramesh.user.entities.Address;
import com.ramesh.user.dtos.AddressDto;
import com.ramesh.user.entities.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface UserMapper {

    //UserRequest to User
    //id and role are never taken from the request: the id comes back from Keycloak,
    //and ignoring role means self-signup always gets the CUSTOMER default, never ADMIN.
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target="updatedAt", ignore = true)
    @Mapping(source="addressDto", target = "address")
    User toEntity(UserRequest request);


    //User to UserResponse
    //no numberFormat on id: it is a Keycloak UUID string now, never a number.
    @Mapping(source = "address", target = "addressDto")
    UserResponse toResponse(User user);

    //Address <-> AddressDto(all filed type/names same)
    AddressDto toAddressDto(Address address);
    @Mapping(target = "id", ignore = true)
    Address toAddress(AddressDto addressDto);

}
