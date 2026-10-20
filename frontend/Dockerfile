# Production SPA: static Vite build served by unprivileged nginx, /api proxied to the gateway.
FROM node:22.23.3-alpine3.24@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402 AS build
WORKDIR /app
COPY package.json package-lock.json ./
# Skips the husky prepare hook: there is no Git checkout inside the build.
RUN npm ci --ignore-scripts
COPY . .
# Vite inlines VITE_* at build time; override with --build-arg for a public deployment.
ARG VITE_API_BASE_URL=/api
ARG VITE_KEYCLOAK_URL=http://localhost:8180
ARG VITE_KEYCLOAK_REALM=deepfake
ARG VITE_KEYCLOAK_CLIENT_ID=deepfake-web
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL \
    VITE_KEYCLOAK_URL=$VITE_KEYCLOAK_URL \
    VITE_KEYCLOAK_REALM=$VITE_KEYCLOAK_REALM \
    VITE_KEYCLOAK_CLIENT_ID=$VITE_KEYCLOAK_CLIENT_ID
# Type checking and tests stay in the frontend CI job.
RUN npx vite build

FROM nginxinc/nginx-unprivileged:1.30.6-alpine3.24@sha256:f5116ed4606f3eb015dd4a8e28cb9b351476dc0ad4bc6e9f93b19cec130cdf03
# The image's entrypoint renders templates with envsubst, only for variables that are set.
ENV GATEWAY_URL=http://gateway:8080
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
