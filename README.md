# ecom_microservices

---
- Install docker desktop in your machine

## Environment variables
- create .env file with following in the project root: 
```
# Shared Postgresql container credentials
POSTGRES_USER=user
POSTGRES_PASSWORD=password

# Mongodb
MONGO_USERNAME=user
MONGO_PASSWORD=password
MONGO_PORT=27018

#Product service
PRODUCT_DB=product_db
PRODUCT_PORT=8081

#User service
USER_DB=user_db
USER_PORT=8082

# Order service
ORDER_DB=order_db
ORDER_PORT=8083

#eureka server
EUREKA_SERVER_PORT=8761


```
- It has services like user-service, product-service, order-service
- With Docker-compose.yml also installs postgresql, mongodb
- `docker compose up -d --build` starts all services with building new images with each Dockerfile.
- `docker compose down -v` stops and deletes all containers with volumes.

## API Endpoints

### Product Service — http://localhost:8081

| Method | Endpoint                      | Description                      |
|--------|--------------------------------|-----------------------------------|
| POST   | /api/products                  | Create a product                 |
| GET    | /api/products                  | Get all products                 |
| GET    | /api/products/{id}              | Get product by ID                |
| PUT    | /api/products/{id}              | Update product by ID             |
| DELETE | /api/products/{id}              | Delete product by ID             |
| GET    | /api/products/search?keyword=   | Search products by keyword       |
| GET    | /api/product/demo/message       | Config hot-reload demo endpoint  |

### Order Service — http://localhost:8083

| Method | Endpoint                       | Description                       | Headers               |
|--------|---------------------------------|-------------------------------------|--------------------------|
| POST   | /api/carts                      | Add item to cart                  | X-User-ID (required)  |
| GET    | /api/carts                      | Get current user's cart           | X-User-ID (required)  |
| DELETE | /api/carts/items/{productId}    | Remove item from cart             | X-User-ID (required)  |
| POST   | /api/orders                     | Create order from cart            | X-User-ID (required)  |
| GET    | /api/order/demo/message         | Config hot-reload demo endpoint   | —                      |

### User Service — http://localhost:8082

| Method | Endpoint          | Description        |
|--------|--------------------|-----------------------|
| GET    | /api/users          | Get all users       |
| GET    | /api/users/{id}      | Get user by ID      |
| POST   | /api/users           | Create a user       |
| PUT    | /api/users/{id}      | Update user by ID   |
| DELETE | /api/users/{id}      | Delete user by ID   |

Note: `X-User-ID` is a plain request header (string), not auth — every cart/order request needs it since there's no auth layer yet.

