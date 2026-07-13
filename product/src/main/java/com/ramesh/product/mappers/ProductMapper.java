package com.ramesh.product.mappers;


import com.ramesh.product.dtos.ProductRequest;
import com.ramesh.product.dtos.ProductResponse;
import com.ramesh.product.entities.Product;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface ProductMapper {
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "active", ignore = true)
    Product toEntity(ProductRequest request);

    //@Mapping(source = "id", target = "id", numberFormat = "#")
    ProductResponse toResponse(Product product);


}
