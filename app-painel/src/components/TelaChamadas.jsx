import React, { useEffect, useState, useRef } from "react";
import keycloak from "../services/keycloak";

const blinkKeyframes = `
@keyframes blink {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.2; }
}
`;

const API_URL = process.env.REACT_APP_API_PAINEL_URL || "";

export default function TelaChamadas({ agenciaId, painelNumero, onDesativar, username }) {
  const [chamadas, setChamadas] = useState([]);
  const [historico, setHistorico] = useState([]);
  const eventSourceRef = useRef(null);

  useEffect(() => {
    conectarSSE();
    return () => {
      if (eventSourceRef.current) eventSourceRef.current.close();
    };
  }, []);

  async function conectarSSE() {
    await keycloak.updateToken(30);
    const url = `${API_URL}/api/painel/sse/${agenciaId}/${painelNumero}?access_token=${keycloak.token}`;
    const es = new EventSource(url);
    eventSourceRef.current = es;

    es.addEventListener("painel-update", (event) => {
      const data = JSON.parse(event.data);
      setChamadas((prev) => atualizarChamadas(prev, data));
      setHistorico((prev) => atualizarHistorico(prev, data));
    });

    es.onerror = () => {
      es.close();
      setTimeout(conectarSSE, 3000);
    };
  }

  function atualizarChamadas(prev, nova) {
    const filtrado = prev.filter((c) => c.senha !== nova.senha);
    if (nova.status === "FINALIZADO") return filtrado;
    return [nova, ...filtrado].slice(0, 10);
  }

  function atualizarHistorico(prev, nova) {
    const entry = { ...nova, timestamp: new Date().toISOString() };
    const filtrado = prev.filter((h) => h.senha !== nova.senha);
    return [entry, ...filtrado];
  }

  const corStatus = { CHAMANDO: "#ff9800", EM_ATENDIMENTO: "#4caf50", AUSENTE: "#f44336", FINALIZADO: "#888" };

  return (
    <div style={{ padding: 24, backgroundColor: "#1a1a2e", minHeight: "100vh", color: "#fff" }}>
      <style>{blinkKeyframes}</style>
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
              ...(c.status === "CHAMANDO" ? { animation: "blink 2.5s ease-in-out infinite" } : {}),
            }}>
              {c.status}
            </div>
          </div>
        ))}
      </div>

      {historico.length > 0 && (
        <div style={{ marginTop: 48 }}>
          <h2 style={{ borderBottom: "1px solid #444", paddingBottom: 8 }}>Histórico de Chamados</h2>
          <table style={{ width: "100%", borderCollapse: "collapse", marginTop: 12 }}>
            <thead>
              <tr style={{ textAlign: "left", opacity: 0.7 }}>
                <th style={{ padding: "8px 12px" }}>Horário</th>
                <th style={{ padding: "8px 12px" }}>Senha</th>
                <th style={{ padding: "8px 12px" }}>Nome</th>
                <th style={{ padding: "8px 12px" }}>Sala</th>
                <th style={{ padding: "8px 12px" }}>Status</th>
              </tr>
            </thead>
            <tbody>
              {historico.map((h) => (
                <tr key={h.senha} style={{ borderTop: "1px solid #333" }}>
                  <td style={{ padding: "8px 12px" }}>{new Date(h.timestamp).toLocaleTimeString()}</td>
                  <td style={{ padding: "8px 12px", fontWeight: "bold" }}>{h.senha}</td>
                  <td style={{ padding: "8px 12px" }}>{h.nomePessoa}</td>
                  <td style={{ padding: "8px 12px" }}>{h.sala}</td>
                  <td style={{ padding: "8px 12px" }}>
                    <span style={{
                      padding: "2px 8px",
                      borderRadius: 4,
                      backgroundColor: corStatus[h.status] || "#666",
                      fontWeight: "bold",
                      fontSize: 12,
                      ...(h.status === "CHAMANDO" ? { animation: "blink 2.5s ease-in-out infinite" } : {}),
                    }}>
                      {h.status}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
