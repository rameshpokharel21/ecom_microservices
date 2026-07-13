package com.ramesh.order.mappers;


import com.ramesh.order.dtos.CartItemResponse;
import com.ramesh.order.entities.CartItem;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.math.BigDecimal;

@Mapper(componentModel = "spring")
public interface CartItemMapper {

    @Mapping(source = "userId", target = "userId")
    @Mapping(source = "productId", target = "productId")
    @Mapping(source = "unitPrice", target = "price")
    @Mapping(target = "subTotal", ignore = true)
    //example: if shippingZipCode in CartItemResponse
        // field name should exactly match
    //@Mapping(source = "user.address.zipcode", target = "shippingZipcode")
    CartItemResponse toResponse(CartItem cartItem);


    @AfterMapping
    default void calculateSubTotal(CartItem cartItem, @MappingTarget CartItemResponse dto){
      if(cartItem.getUnitPrice() != null && cartItem.getQuantity() != null){
          dto.setSubTotal(cartItem.getUnitPrice().multiply(BigDecimal.valueOf(cartItem.getQuantity())));
      }
    }

}
