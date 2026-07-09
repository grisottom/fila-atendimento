import React, { useState } from "react";
import keycloak from "./services/keycloak";
import ConfiguracaoPainel from "./components/ConfiguracaoPainel";
import TelaChamadas from "./components/TelaChamadas";

export default function App() {
  const [config, setConfig] = useState(null);

  if (!keycloak.hasRealmRole("admin")) {
    return (
      <div style={{ padding: 40, textAlign: "center" }}>
        <h2>Acesso negado</h2>
        <p>Somente usuários com papel Admin podem ativar o Painel.</p>
        <button onClick={() => keycloak.logout()}>Sair</button>
      </div>
    );
  }

  const username = keycloak.tokenParsed?.preferred_username;

  if (!config) {
    return <ConfiguracaoPainel onAtivar={(agenciaId, painelNumero) => setConfig({ agenciaId, painelNumero })} username={username} />;
  }

  return (
    <TelaChamadas
      agenciaId={config.agenciaId}
      painelNumero={config.painelNumero}
      onDesativar={() => setConfig(null)}
      username={username}
    />
  );
}
