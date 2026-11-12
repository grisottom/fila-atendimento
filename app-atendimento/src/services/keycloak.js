import Keycloak from "keycloak-js";
// Realm e client_id podem ser trocados no build (VITE_KEYCLOAK_REALM / VITE_KEYCLOAK_CLIENT_ID).
const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL || "http://localhost:8080",
  realm: import.meta.env.VITE_KEYCLOAK_REALM || "fila-atendimento",
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || "fila-atendimento",
});
export default keycloak;
