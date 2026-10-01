# 빌드: JDK 25 이미지에서 bootJar 생성 (toolchain 25와 일치 → JDK 추가 다운로드 없음)
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew bootJar --no-daemon -q

# 실행: JRE만 포함
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/build/libs/*-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
