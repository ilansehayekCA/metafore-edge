# Stage 1: Build
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jdk-alpine AS build
RUN apk add --no-cache maven
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

# Stage 2: Runtime
FROM public.ecr.aws/docker/library/eclipse-temurin:21-jre-alpine
RUN apk add --no-cache procps
WORKDIR /app
COPY --from=build /build/target/metafore-edge-1.0.0-jar-with-dependencies.jar app.jar

ENV CONTROLLER_ID=edge-default \
    TENANT_ID=default-tenant \
    BROKER_URL=tcp://mqtt-broker:1883 \
    EDGE_VERSION=1.0.0

CMD ["java", "-jar", "app.jar"]
