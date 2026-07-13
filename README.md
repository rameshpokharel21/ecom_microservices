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

#pgAdmin
PGADMIN_DEFAULT_EMAIL=admin@admin.com
PGADMIN_DEFAULT_PASSWORD=admin

```
---
