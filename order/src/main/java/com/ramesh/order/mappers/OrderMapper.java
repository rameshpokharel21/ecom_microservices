package com.ramesh.order.mappers;

import com.ramesh.order.dtos.OrderResponse;
import com.ramesh.order.entities.Order;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring", uses = {OrderItemMapper.class})
public interface OrderMapper {

    public OrderResponse toResponse(Order order);
}
