FROM node:24.19.0-alpine AS frontend
WORKDIR /workspace/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk AS backend
WORKDIR /workspace
COPY backend/ ./backend/
COPY --from=frontend /workspace/frontend/dist ./backend/src/main/resources/static/
RUN cd backend \
    && ./gradlew clean bootJar --no-daemon \
    && find /tmp -path '*/libs/minigame-backend-0.0.1-SNAPSHOT.jar' -exec cp '{}' /workspace/app.jar \;

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=backend /workspace/app.jar /app/app.jar
RUN useradd --system --uid 10001 --no-create-home appuser
USER 10001
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
