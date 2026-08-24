package com.ramesh.user.services;


import com.ramesh.user.dtos.UserRequest;
import com.ramesh.user.dtos.UserResponse;
import com.ramesh.user.entities.User;
import com.ramesh.user.entities.UserRole;
import com.ramesh.user.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import com.ramesh.user.mappers.UserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UserService {

    private static final Logger logger = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final KeyCloakAdminService keyCloakAdminService;

    public List<UserResponse> fetchAllUsers(){
        //return userList;
         return userRepository.findAll()
                 .stream()
                 .map(u -> userMapper.toResponse(u))
                 .toList();
    }

    /**
     * Keycloak is the identity source: it creates the account and hands back the id,
     * which becomes the Mongo _id. That equality is what makes the whole chain resolve -
     * the gateway relays the token's "sub" as X-User-ID and order-service looks the user
     * up by it, so a Mongo-generated ObjectId here would 404 every cart call.
     *
     * <p>Keycloak is written first and cannot join the local transaction, so a failed
     * save would strand an account that can log in, has no profile, and blocks
     * re-registration with a 409 on the username. The catch below compensates for that.
     */
    public UserResponse addUser(UserRequest requestUser){
        if(requestUser == null){
            throw new RuntimeException("null cannot be user");
        }

        String token = keyCloakAdminService.getAdminAccessToken();
        String keycloakUserId = keyCloakAdminService.createUser(token, requestUser);

        try {
            User user = userMapper.toEntity(requestUser);
            //_id == Keycloak id == the "sub" claim. Not a second keycloakId field.
            user.setId(keycloakUserId);
            //Self-signup is always CUSTOMER - the role is named by the enum here and
            //stored only in Keycloak. Promotion to ADMIN is a Keycloak console action,
            //deliberately not something this endpoint can be talked into doing.
            keyCloakAdminService.assignRealmRoleToUser(
                    token, keycloakUserId, UserRole.CUSTOMER.name());

            User savedUser = userRepository.save(user);
            return userMapper.toResponse(savedUser);
        } catch (RuntimeException e) {
            try {
                keyCloakAdminService.deleteUser(token, keycloakUserId);
            } catch (RuntimeException cleanupFailure) {
                //Never let the rollback hide why the request actually failed.
                e.addSuppressed(cleanupFailure);
                logger.error("Orphaned Keycloak user {} - rollback failed", keycloakUserId, cleanupFailure);
            }
            throw e;
        }
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


    /**
     * Deletes BOTH halves. Mongo-only was the mirror of the orphan addUser compensates
     * for: the profile vanished while the Keycloak account stayed, so the user kept a
     * valid token, kept passing the gateway, and 404'd on every call that resolves them.
     *
     * <p>Mongo first, Keycloak second, deliberately. Keycloak cannot join this
     * transaction either way, so one order has to be picked and the question is which
     * failure is recoverable. Deleting Keycloak first and then failing leaves a profile
     * nobody can ever log in as - unreachable and undeletable through this endpoint,
     * because findById still succeeds but the account it names is gone. This order fails
     * the other way: the account survives without a profile, which is exactly the state
     * addUser already knows how to produce and an admin can clear in the console.
     */
    public void removeUser(String id){
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("User not found"));
        userRepository.delete(user);

        try {
            keyCloakAdminService.deleteUser(keyCloakAdminService.getAdminAccessToken(), id);
        } catch (RuntimeException e) {
            //Not rethrown: the profile IS gone, so reporting failure would be a lie that
            //invites a retry which then 404s. Loud log instead - this needs a human.
            logger.error("Deleted profile {} but its Keycloak account survives - "
                    + "remove it in the console or it can still obtain tokens", id, e);
        }
    }
}
