import React, { useState, useEffect } from "react";
import { api } from "../services/api";
import keycloak from "../services/keycloak";

function getStorageKey() {
  return `app_agencia_${keycloak.tokenParsed?.preferred_username}`;
}

export default function Atendentes() {
  const [agenciaId, setAgenciaId] = useState(localStorage.getItem(getStorageKey()) || "");
  const [servicos, setServicos] = useState([]);
  const [atendentes, setAtendentes] = useState([]);

  useEffect(() => {
    if (agenciaId) carregar();
  }, []);

  async function carregar() {
    localStorage.setItem(getStorageKey(), agenciaId);
    try {
      const [svcs, atds] = await Promise.all([
        api.get("/api/admin/servicos"),
        api.get(`/api/admin/atendentes/${agenciaId}`),
      ]);
      setServicos(svcs || []);
      setAtendentes(atds || []);
    } catch (e) { /* ignora */ }
  }

  // Intersecção: atendente pode realizar o serviço se possui a permissão exigida
  const interseccao = [];
  servicos.forEach((s) => {
    atendentes.forEach((a) => {
      if (a.roles.includes(s.permissaoExigida)) {
        interseccao.push({ servico: s.nome || s.id, atendente: a.username, permissao: s.permissaoExigida });
      }
    });
  });

  // Serviços sem atendentes
  const servicosSemAtendente = servicos.filter((s) =>
    !atendentes.some((a) => a.roles.includes(s.permissaoExigida))
  );

  // Atendentes sem serviço
  const permissoesServicos = [...new Set(servicos.map((s) => s.permissaoExigida))];
  const atendentesSemServico = atendentes.filter((a) =>
    !a.roles.some((r) => permissoesServicos.includes(r))
  );

  const cellStyle = { padding: "6px 10px" };
  const headerStyle = { textAlign: "left", borderBottom: "2px solid #ccc" };

  return (
    <div>
      <h2>Atendentes da Agência</h2>
      <div style={{ marginBottom: 16 }}>
        <label>Agência: </label>
        <input value={agenciaId} onChange={(e) => setAgenciaId(e.target.value)} placeholder="agencia-01" />
        <button onClick={carregar} style={{ marginLeft: 8 }}>Carregar</button>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 24 }}>
        {/* 1) Serviços à esquerda */}
        <div>
          <h3>Serviços do Dia</h3>
          <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead><tr style={headerStyle}>
              <th style={cellStyle}>Serviço</th>
              <th style={cellStyle}>Permissão</th>
            </tr></thead>
            <tbody>
              {servicos.map((s) => (
                <tr key={s.id} style={{ borderBottom: "1px solid #eee" }}>
                  <td style={cellStyle}>{s.nome || s.id}</td>
                  <td style={cellStyle}>{s.permissaoExigida}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* 3) Intersecção no meio */}
        <div>
          <h3>Serviços × Atendentes</h3>
          <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead><tr style={headerStyle}>
              <th style={cellStyle}>Serviço</th>
              <th style={cellStyle}>Atendente</th>
            </tr></thead>
            <tbody>
              {interseccao.map((item, i) => (
                <tr key={i} style={{ borderBottom: "1px solid #eee" }}>
                  <td style={cellStyle}>{item.servico}</td>
                  <td style={cellStyle}>{item.atendente}</td>
                </tr>
              ))}
              {interseccao.length === 0 && <tr><td colSpan={2} style={cellStyle}>Nenhuma correspondência</td></tr>}
            </tbody>
          </table>
        </div>

        {/* 2) Atendentes à direita */}
        <div>
          <h3>Atendentes</h3>
          <table style={{ width: "100%", borderCollapse: "collapse" }}>
            <thead><tr style={headerStyle}>
              <th style={cellStyle}>Usuário</th>
              <th style={cellStyle}>Permissões</th>
            </tr></thead>
            <tbody>
              {atendentes.map((a) => (
                <tr key={a.username} style={{ borderBottom: "1px solid #eee" }}>
                  <td style={cellStyle}>{a.username}</td>
                  <td style={cellStyle}>{a.roles.join(", ")}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Linha inferior */}
      <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 24, marginTop: 32 }}>
        {/* 4) Serviços sem atendentes */}
        <div>
          <h3 style={{ color: "#c62828" }}>Serviços sem Atendentes</h3>
          {servicosSemAtendente.length === 0 ? <p>Todos os serviços têm atendentes</p> : (
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead><tr style={headerStyle}>
                <th style={cellStyle}>Serviço</th>
                <th style={cellStyle}>Permissão Exigida</th>
              </tr></thead>
              <tbody>
                {servicosSemAtendente.map((s) => (
                  <tr key={s.id} style={{ borderBottom: "1px solid #eee" }}>
                    <td style={cellStyle}>{s.nome || s.id}</td>
                    <td style={cellStyle}>{s.permissaoExigida}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {/* 5) Atendentes sem serviço */}
        <div>
          <h3 style={{ color: "#c62828" }}>Atendentes sem Serviço</h3>
          {atendentesSemServico.length === 0 ? <p>Todos os atendentes têm serviços</p> : (
            <table style={{ width: "100%", borderCollapse: "collapse" }}>
              <thead><tr style={headerStyle}>
                <th style={cellStyle}>Usuário</th>
                <th style={cellStyle}>Permissões</th>
              </tr></thead>
              <tbody>
                {atendentesSemServico.map((a) => (
                  <tr key={a.username} style={{ borderBottom: "1px solid #eee" }}>
                    <td style={cellStyle}>{a.username}</td>
                    <td style={cellStyle}>{a.roles.join(", ")}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}
