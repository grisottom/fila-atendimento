FROM docker.io/library/node:20-alpine AS build
WORKDIR /app
COPY package.json ./
RUN npm install
COPY index.html ./
COPY src ./src
# Build-arg opcional com a URL pública do Keycloak (default vazio → o front cai em
# http://localhost:8080). O Vite embute variáveis VITE_* no momento do build.
ARG VITE_KEYCLOAK_URL=""
ENV VITE_KEYCLOAK_URL=${VITE_KEYCLOAK_URL}
RUN npm run build

FROM docker.io/library/nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
