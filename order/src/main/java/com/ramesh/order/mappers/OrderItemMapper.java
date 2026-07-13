package com.ramesh.order.mappers;

import com.ramesh.order.dtos.OrderItemDto;
import com.ramesh.order.entities.OrderItem;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.math.BigDecimal;

@Mapper(componentModel = "spring")
public interface OrderItemMapper {
    @Mapping(source = "productId", target = "productId")
    @Mapping(source = "unitPrice", target = "price")
    @Mapping(target = "subTotal", ignore = true)
    public OrderItemDto toOrderItemDto(OrderItem orderItem);

    @AfterMapping
    default void calculateSubTotal(OrderItem orderItem, @MappingTarget OrderItemDto dto){
        if(orderItem.getUnitPrice() != null && orderItem.getQuantity() != null){
            dto.setSubTotal(orderItem.getUnitPrice().multiply(BigDecimal.valueOf(orderItem.getQuantity())));
        }
    }
}
