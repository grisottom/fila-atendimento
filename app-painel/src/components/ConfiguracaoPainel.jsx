import React, { useState } from "react";
import keycloak from "../services/keycloak";

export default function ConfiguracaoPainel({ onAtivar, username }) {
  const agenciaDoUsuario = keycloak.tokenParsed?.agencia || "";
  const [agenciaId, setAgenciaId] = useState(agenciaDoUsuario);
  const [painelNumero, setPainelNumero] = useState("");

  function ativar(e) {
    e.preventDefault();
    if (agenciaId && painelNumero) {
      onAtivar(agenciaId, Number(painelNumero));
    }
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", alignItems: "center", marginTop: 80 }}>
      <p>Logado como: <strong>{username}</strong> | <button onClick={() => keycloak.logout()}>Sair</button></p>
      <h2>Ativação do Painel</h2>
      <form onSubmit={ativar} style={{ display: "flex", flexDirection: "column", gap: 12, width: 300 }}>
        <label>
          Agência:
          <input value={agenciaId} onChange={(e) => setAgenciaId(e.target.value)} placeholder="agencia-01" required style={{ width: "100%" }} />
        </label>
        <label>
          Número do Painel:
          <input type="number" value={painelNumero} onChange={(e) => setPainelNumero(e.target.value)} placeholder="1" required style={{ width: "100%" }} />
        </label>
        <button type="submit">Ativar Painel</button>
      </form>
    </div>
  );
}
