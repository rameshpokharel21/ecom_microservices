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

    /*
     * The /me trio. These take NO id: the only input is the X-User-ID header, which the
     * gateway strips from the request and rewrites from the token's "sub", so a caller
     * cannot address another account even deliberately. That is the same structural
     * authorization the cart and order endpoints have always had, and it is why the
     * /{id} variants below are now ADMIN-only - they take an id from the client, so
     * without a role check any logged-in user could read, edit or delete any account.
     *
     * No mapping conflict with /{id}: a literal path segment outranks a template in
     * Spring's path matching, regardless of declaration order.
     */
    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser(@RequestHeader("X-User-ID") String userId){
        return userService.fetchUserById(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateCurrentUser(@RequestBody UserRequest updatedUser,
                                                          @RequestHeader("X-User-ID") String userId){
        return userService.updateUser(updatedUser, userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteCurrentUser(@RequestHeader("X-User-ID") String userId){
        try{
            userService.removeUser(userId);
            return ResponseEntity.noContent().build();
        }catch (NoSuchElementException e){
            return ResponseEntity.notFound().build();
        }
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
