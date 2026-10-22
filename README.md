# TaskFlow — Backend

API REST de TaskFlow, un gestor de tareas inspirado en Plane (solo como referencia funcional).

## Requisitos

- Java 21 o superior
- Docker (para PostgreSQL)

## Cómo levantarlo

```bash
cp .env.example .env          # y completar JWT_SECRET (mínimo 32 caracteres)
docker compose up -d          # PostgreSQL 16 en localhost:5432
./mvnw spring-boot:run        # API en http://localhost:8080
```

Swagger UI: http://localhost:8080/swagger-ui.html
