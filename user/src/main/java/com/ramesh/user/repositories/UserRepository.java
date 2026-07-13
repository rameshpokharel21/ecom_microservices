package com.ramesh.user.repositories;


import com.ramesh.user.entities.User;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserRepository extends MongoRepository<User, String> {
}
