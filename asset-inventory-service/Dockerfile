# Imagen del servicio asset-inventory-service.
# Contexto de build = raíz del repo backend (para el POM padre, contracts/ y checkstyle-suppressions).
# Etapa 1 — build con Maven + JDK 21 (el POM padre exige un toolchain jdk 21: se genera en la imagen).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
RUN mkdir -p /root/.m2 && \
    printf '<?xml version="1.0" encoding="UTF-8"?>\n<toolchains>\n <toolchain>\n  <type>jdk</type>\n  <provides><version>21</version></provides>\n  <configuration><jdkHome>%s</jdkHome></configuration>\n </toolchain>\n</toolchains>\n' "$JAVA_HOME" > /root/.m2/toolchains.xml
COPY pom.xml checkstyle-suppressions.xml ./
COPY contracts/ contracts/
COPY asset-inventory-service/ asset-inventory-service/
RUN mvn -B -pl asset-inventory-service -am -DskipTests clean package

# Etapa 2 — runtime ligero (solo JRE).
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/asset-inventory-service/target/asset-inventory-service-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
