package com.ramesh.product.services;


import com.ramesh.product.dtos.ProductRequest;
import com.ramesh.product.dtos.ProductResponse;
import com.ramesh.product.entities.Product;
import com.ramesh.product.mappers.ProductMapper;
import com.ramesh.product.repositories.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductMapper productMapper;

    public ProductResponse addProduct(ProductRequest productRequest){
        if(productRequest == null){
            throw new RuntimeException("Product request cannot be null");
        }

        Product product = productMapper.toEntity(productRequest);
        return productMapper.toResponse(productRepository.save(product));
    }

    private void updateProductFromRequest(Product product, ProductRequest productRequest){
        product.setName(productRequest.getName());
        product.setDescription(productRequest.getDescription());
        product.setPrice(productRequest.getPrice());
        product.setCategory(productRequest.getCategory());
        product.setImageUrl(productRequest.getImageUrl());
        product.setStockQuantity(productRequest.getStockQuantity());
    }

    public Optional<ProductResponse> updateProduct(Long id, ProductRequest productRequest){
        return productRepository.findById(id)
                .map(existingProduct -> {
                    updateProductFromRequest(existingProduct, productRequest);
                    Product savedProduct = productRepository.save(existingProduct);
                    return productMapper.toResponse(savedProduct);
                });
    }

    public List<ProductResponse> getAllProducts() {
        return productRepository.findByActiveTrue()
                .stream()
                .map(product -> productMapper.toResponse(product))
                .toList();
    }

    public Optional<ProductResponse> getProductById(Long id) {
        return productRepository.findById(id)
                .map(product -> productMapper.toResponse(product));
    }

    public boolean deleteProduct(Long id) {
        return  productRepository.findById(id)
                .map(product -> {
                    product.setActive(false);
                    productRepository.save(product);
                    return true;
                })
                .orElse(false);

    }

    public @Nullable List<ProductResponse> searchProducts(String keyword) {
        return productRepository.searchProducts(keyword).stream()
                .map(p -> productMapper.toResponse(p))
                .toList();
    }
}
