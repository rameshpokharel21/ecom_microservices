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
    //The id is never taken from the request - it comes back from Keycloak. There is no
    //role to ignore any more: the entity no longer stores one.
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target="updatedAt", ignore = true)
    //No @Mapping for address: DTO and entity now share the name, so MapStruct pairs them
    //itself and routes the conversion through toAddress/toAddressDto below.
    User toEntity(UserRequest request);


    //User to UserResponse
    //no numberFormat on id: it is a Keycloak UUID string now, never a number.
    UserResponse toResponse(User user);

    //Address <-> AddressDto(all filed type/names same)
    AddressDto toAddressDto(Address address);
    @Mapping(target = "id", ignore = true)
    Address toAddress(AddressDto addressDto);

}
