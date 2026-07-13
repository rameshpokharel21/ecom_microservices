package com.ramesh.user.services;


import com.ramesh.user.dtos.UserRequest;
import com.ramesh.user.dtos.UserResponse;
import com.ramesh.user.entities.User;
import com.ramesh.user.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import com.ramesh.user.mappers.UserMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    public List<UserResponse> fetchAllUsers(){
        //return userList;
         return userRepository.findAll()
                 .stream()
                 .map(u -> userMapper.toResponse(u))
                 .toList();
    }

    public UserResponse addUser(UserRequest requestUser){
        if(requestUser == null){
            throw new RuntimeException("null cannot be user");
        }
        User user = userMapper.toEntity(requestUser);
        User savedUser =  userRepository.save(user);
        return userMapper.toResponse(savedUser);

    }

    public Optional<UserResponse> fetchUserById(String id) {

        return userRepository.findById(id)
                //.map(userMapper::toResponse);
                .map(u -> userMapper.toResponse(u));

    }



    public Optional<UserResponse> updateUser(UserRequest userRequest, String id) {

        return userRepository.findById(id)
                .map(userFromData -> {
                    userFromData.setFirstName(userRequest.getFirstName());
                    userFromData.setLastName(userRequest.getLastName());
                    userFromData.setEmail(userRequest.getEmail());
                    userFromData.setPhone(userRequest.getPhone());
                    userFromData.setAddress(userMapper.toAddress(userRequest.getAddressDto()));

                    User savedUser = userRepository.save(userFromData);
                    return userMapper.toResponse(savedUser);
                });
    }


    public void removeUser(String id){
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found"));
        userRepository.delete(user);
    }
}
