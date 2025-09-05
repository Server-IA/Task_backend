# Etapa 1: Compilación con Maven y JDK 21
# Usamos una imagen oficial de Maven con OpenJDK 21 para compilar el proyecto.
FROM maven:3.9.6-eclipse-temurin-21 AS build

# Establecemos el directorio de trabajo dentro del contenedor
WORKDIR /app

# Copiamos el pom.xml para descargar las dependencias y aprovechar el cache de Docker
COPY pom.xml .

# Descargamos todas las dependencias del proyecto
RUN mvn dependency:go-offline -B

# Copiamos el resto del código fuente del proyecto
COPY src ./src

# Compilamos la aplicación usando el perfil '''local''' y omitiendo tests
RUN mvn clean package -DskipTests

# Etapa 2: Ejecución
# Usamos una imagen de JRE (Java Runtime Environment) más ligera para la ejecución.
# Debe coincidir con la versión de Java usada en la etapa de compilación.
FROM eclipse-temurin:21-jre-jammy

# Instalar fuentes de Microsoft (incluyendo Arial)
USER root
RUN apt-get update && \
    echo ttf-mscorefonts-installer msttcorefonts/accepted-mscorefonts-eula select true | debconf-set-selections && \
    apt-get install -y ttf-mscorefonts-installer && \
    rm -rf /var/lib/apt/lists/*

# Establecemos el directorio de trabajo
WORKDIR /app

# Exponemos el puerto 8080, que es el puerto por defecto para Spring Boot.
# Si tu aplicación corre en un puerto diferente, cámbialo aquí.
EXPOSE 8080

# Copiamos el archivo .jar compilado desde la etapa de '''build''' a la imagen final
# El nombre del JAR se define en el pom.xml (<artifactId>-<version>.jar)
COPY --from=build /app/target/task-0.0.1.jar app.jar

# Comando para ejecutar la aplicación cuando se inicie el contenedor
ENTRYPOINT ["java", "-jar", "app.jar"]