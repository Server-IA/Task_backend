# Imagen de la API de TaskFlow (para Dokploy): compila con Maven y corre con un JRE, como un usuario sin privilegios.
# La configuración llega por variables de entorno (ver .env.example y DESPLIEGUE.md); en el contenedor no hay .env.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S taskflow && adduser -S -G taskflow taskflow
COPY --from=build --chown=taskflow:taskflow /app/target/*.jar app.jar
USER taskflow
# La memoria de la JVM sigue el límite del contenedor; si se queda sin memoria, el contenedor se reinicia en vez de
# quedar a medias. Detrás de Dokploy (Traefik) la IP real del cliente llega en X-Forwarded-For, y Swagger va apagado
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    FORWARD_HEADERS_STRATEGY=native \
    SPRINGDOC_ENABLED=false
EXPOSE 8080
# Lista cuando responde y llega a la base de datos (Flyway corre al arrancar: la primera vez tarda un poco más)
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/api/v1/health > /dev/null || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
