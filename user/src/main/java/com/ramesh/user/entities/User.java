package com.ramesh.user.entities;


import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data

@Document(collection = "user_table")
public class User {
    //Equals the Keycloak user id, i.e. the JWT "sub" the gateway relays as X-User-ID.
    @Id
    private String id;
    private String firstName;
    private String lastName;
    @Indexed(unique = true)
    private String email;
    private String phone;

    //No role field. It was written once at signup and never again, so assigning a role in
    //the Keycloak console left this saying CUSTOMER for an actual ADMIN. Keycloak is the
    //system of record for roles and nothing syncs backward; the token's realm_access.roles
    //is the only correct source, and it is what the gateway authorizes against.
    private Address address;

    @CreatedDate
    private LocalDateTime createdAt;
    @LastModifiedDate
    private LocalDateTime updatedAt;



}
