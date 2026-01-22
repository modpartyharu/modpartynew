FROM gradle:8.5-jdk21 AS build
WORKDIR /app

# Node 설치
RUN apt-get update && apt-get install -y curl && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y nodejs && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# ================================
# 1️⃣ Gradle 설정 & 의존성 레이어
# ================================
COPY gradle.properties ./
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle

RUN gradle dependencies \
    --no-daemon \
    --configuration-cache

# ================================
# 2️⃣ 애플리케이션 소스
# ================================
COPY package.json tailwind.config.js ./
COPY src ./src

RUN npm install && npm run build:css

# ================================
# 3️⃣ Gradle 빌드
# ================================
RUN gradle build -x test \
    --no-daemon \
    --build-cache \
    --configuration-cache

# ================================
# Runtime
# ================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
