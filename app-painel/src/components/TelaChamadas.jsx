import React, { useEffect, useState, useRef } from "react";
import keycloak from "../services/keycloak";

const API_URL = process.env.REACT_APP_API_PAINEL_URL || "";

export default function TelaChamadas({ agenciaId, painelNumero, onDesativar, username }) {
  const [chamadas, setChamadas] = useState([]);
  const eventSourceRef = useRef(null);

  useEffect(() => {
    conectarSSE();
    return () => {
      if (eventSourceRef.current) eventSourceRef.current.close();
    };
  }, []);

  async function conectarSSE() {
    await keycloak.updateToken(30);
    // EventSource não suporta headers customizados, então o JWT é enviado via query param.
    // No backend, o SseTokenFilter extrai esse param e injeta como header Authorization.
    const url = `${API_URL}/api/painel/sse/${agenciaId}/${painelNumero}?access_token=${keycloak.token}`;
    const es = new EventSource(url);
    eventSourceRef.current = es;

    es.addEventListener("painel-update", (event) => {
      const data = JSON.parse(event.data);
      setChamadas((prev) => atualizarChamadas(prev, data));
    });

    es.onerror = () => {
      es.close();
      // reconectar após 3s
      setTimeout(conectarSSE, 3000);
    };
  }

  function atualizarChamadas(prev, nova) {
    // Remove entrada anterior da mesma senha
    const filtrado = prev.filter((c) => c.senha !== nova.senha);
    // Só mantém se não for FINALIZADO
    if (nova.status === "FINALIZADO") return filtrado;
    return [nova, ...filtrado].slice(0, 10); // máximo 10 visíveis
  }

  const corStatus = { CHAMANDO: "#ff9800", EM_ATENDIMENTO: "#4caf50", AUSENTE: "#f44336" };

  return (
    <div style={{ padding: 24, backgroundColor: "#1a1a2e", minHeight: "100vh", color: "#fff" }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h1>Painel {painelNumero} — {agenciaId}</h1>
        <span>
          {username} | <button onClick={onDesativar} style={{ padding: "8px 16px" }}>Desativar</button>
        </span>
      </div>

      {chamadas.length === 0 && (
        <p style={{ textAlign: "center", marginTop: 60, fontSize: 24, opacity: 0.5 }}>Aguardando chamadas...</p>
      )}

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(280px, 1fr))", gap: 16, marginTop: 24 }}>
        {chamadas.map((c) => (
          <div key={c.senha} style={{
            padding: 24,
            borderRadius: 12,
            backgroundColor: "#16213e",
            borderLeft: `6px solid ${corStatus[c.status] || "#666"}`,
          }}>
            <div style={{ fontSize: 36, fontWeight: "bold" }}>{c.senha}</div>
            <div style={{ fontSize: 18, marginTop: 8 }}>{c.nomePessoa}</div>
            <div style={{ marginTop: 8, opacity: 0.8 }}>Sala: {c.sala}</div>
            <div style={{
              marginTop: 8,
              padding: "4px 12px",
              display: "inline-block",
              borderRadius: 4,
              backgroundColor: corStatus[c.status] || "#666",
              fontWeight: "bold",
              fontSize: 14,
            }}>
              {c.status}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
