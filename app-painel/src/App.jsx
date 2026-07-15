import React, { useState } from "react";
import keycloak from "./services/keycloak";
import ConfiguracaoPainel from "./components/ConfiguracaoPainel";
import TelaChamadas from "./components/TelaChamadas";

function getStorageKey(username) {
  return `painel_config_${username}`;
}

export default function App() {
  const username = keycloak.tokenParsed?.preferred_username;
  const saved = JSON.parse(localStorage.getItem(getStorageKey(username)) || "null");
  const [config, setConfig] = useState(saved);

  if (!keycloak.hasRealmRole("admin")) {
    return (
      <div style={{ padding: 40, textAlign: "center" }}>
        <h2>Acesso negado</h2>
        <p>Somente usuários com papel Admin podem ativar o Painel.</p>
        <button onClick={() => keycloak.logout()}>Sair</button>
      </div>
    );
  }

  function ativar(agenciaId, painelNumero) {
    const c = { agenciaId, painelNumero };
    localStorage.setItem(getStorageKey(username), JSON.stringify(c));
    setConfig(c);
  }

  function desativar() {
    if (config) {
      const sk = `painel-${config.agenciaId}-${config.painelNumero}`;
      localStorage.removeItem(`${sk}-chamadas`);
      localStorage.removeItem(`${sk}-historico`);
    }
    localStorage.removeItem(getStorageKey(username));
    setConfig(null);
  }

  if (!config) {
    return <ConfiguracaoPainel onAtivar={ativar} username={username} />;
  }

  return (
    <TelaChamadas
      agenciaId={config.agenciaId}
      painelNumero={config.painelNumero}
      onDesativar={desativar}
      username={username}
    />
  );
}
