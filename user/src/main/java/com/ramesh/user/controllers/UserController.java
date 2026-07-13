package com.ramesh.user.controllers;

import com.ramesh.user.dtos.UserRequest;
import com.ramesh.user.dtos.UserResponse;
import com.ramesh.user.services.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {
   private final UserService userService;
    @GetMapping()
    public  ResponseEntity<List<UserResponse>>  getAllUsers(){
        List<UserResponse> userList = userService.fetchAllUsers();
        return ResponseEntity.ok(userList);
    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable("id") String id){

        return userService.fetchUserById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@RequestBody UserRequest userRequest){
        UserResponse response = userService.addUser(userRequest);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(@RequestBody UserRequest updatedUser,
                                           @PathVariable("id") String id){
        return userService.updateUser(updatedUser, id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());


    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable("id") String userId){
        try{
            userService.removeUser(userId);
            return ResponseEntity.ok("User deleted successfully.");
        }catch (NoSuchElementException e){
            return ResponseEntity.notFound().build();
        }catch (Exception e){
            return new ResponseEntity<>(e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
