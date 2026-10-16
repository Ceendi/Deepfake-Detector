# Keycloak with the login theme built from frontend sources (context: frontend/), so the
# auth profile needs neither a committed JAR nor Node.js, Java or Maven on the host.
FROM maven:3.9.15-eclipse-temurin-21-alpine@sha256:856fe78872c59d3b584a64d07693d5433cbf44e35b5ba1f2c911147f39f54266 AS theme
RUN apk add --no-cache nodejs npm
WORKDIR /frontend
COPY package.json package-lock.json ./
# Skips the husky prepare hook: there is no Git checkout inside the build.
RUN npm ci --ignore-scripts
COPY . .
# Type checking stays in the frontend CI job; tests import fixtures outside this context.
RUN npx vite build && npx keycloakify build

FROM quay.io/keycloak/keycloak:26.8.1
COPY --from=theme /frontend/dist_keycloak/keycloak-theme.jar /opt/keycloak/providers/keycloak-theme.jar
USER 1000
