FROM docker.io/library/node:20-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install
COPY index.html ./
COPY src ./src
# Build-arg opcional para experimentos de renovação de token (default: vazio →
# o front cai no default de 10 min). O Vite lê variáveis VITE_* do ambiente de build.
# VITE_KEYCLOAK_URL: URL pública do Keycloak (default vazio → http://localhost:8080).
ARG VITE_KEYCLOAK_URL=""
ENV VITE_KEYCLOAK_URL=${VITE_KEYCLOAK_URL}
ARG VITE_TOKEN_REFRESH_MS=""
ENV VITE_TOKEN_REFRESH_MS=${VITE_TOKEN_REFRESH_MS}
RUN npm run build

FROM docker.io/library/nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
