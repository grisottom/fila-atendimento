import React, { useState, useEffect } from "react";
import { api } from "../services/api";
import keycloak from "../services/keycloak";

function getStorageKey() {
  return `app_agencia_${keycloak.tokenParsed?.preferred_username}`;
}

export default function Configuracao() {
  const [paineis, setPaineis] = useState([]);
  const [estacoes, setEstacoes] = useState([]);
  const [agenciaId, setAgenciaId] = useState(localStorage.getItem(getStorageKey()) || "");
  const [msg, setMsg] = useState("");

  // Painel form
  const [painelNumero, setPainelNumero] = useState("");
  const [painelLocal, setPainelLocal] = useState("");

  // Estacao form
  const [estacaoTipo, setEstacaoTipo] = useState("MESA");
  const [estacaoNumero, setEstacaoNumero] = useState("");
  const [estacaoLocal, setEstacaoLocal] = useState("");
  const [estacaoPainelId, setEstacaoPainelId] = useState("");

  useEffect(() => {
    if (agenciaId) carregarDados();
  }, [agenciaId]);

  async function carregarDados() {
    localStorage.setItem(getStorageKey(), agenciaId);
    try {
      setPaineis(await api.get(`/api/admin/painel/${agenciaId}`));
      setEstacoes(await api.get(`/api/admin/estacao/${agenciaId}`));
    } catch (e) {
      setMsg("Erro ao carregar: " + e.message);
    }
  }

  async function criarPainel(e) {
    e.preventDefault();
    try {
      await api.post("/api/admin/painel", { agenciaId, numeroPainel: Number(painelNumero), localizacao: painelLocal });
      setPainelNumero(""); setPainelLocal("");
      carregarDados();
      setMsg("Painel criado");
    } catch (err) { setMsg(err.message); }
  }

  async function criarEstacao(e) {
    e.preventDefault();
    try {
      await api.post("/api/admin/estacao", { agenciaId, tipoEstacao: estacaoTipo, numeroEstacao: Number(estacaoNumero), localizacao: estacaoLocal, painelId: Number(estacaoPainelId) });
      setEstacaoTipo("MESA"); setEstacaoNumero(""); setEstacaoLocal(""); setEstacaoPainelId("");
      carregarDados();
      setMsg("Estação criada");
    } catch (err) { setMsg(err.message); }
  }

  async function excluirPainel(id) {
    try {
      await api.delete(`/api/admin/painel/${id}`);
      carregarDados();
      setMsg("Painel excluído");
    } catch (err) { setMsg(err.message); }
  }

  async function excluirEstacao(id) {
    try {
      await api.delete(`/api/admin/estacao/${id}`);
      carregarDados();
      setMsg("Estação excluída");
    } catch (err) { setMsg(err.message); }
  }


  function nomeExibicao(e) {
    return (e.tipoEstacao === "GUICHE" ? "Guichê" : "Mesa") + " " + e.numeroEstacao;
  }

  return (
    <div>
      <h2>Configuração da Agência</h2>
      <label>Agência ID: </label>
      <input value={agenciaId} onChange={(e) => setAgenciaId(e.target.value)} placeholder="ex: agencia-01" />
      <button onClick={carregarDados}>Carregar</button>

      {msg && <p style={{ color: "blue" }}>{msg}</p>}

      <hr />
      <h3>Painéis</h3>
      <ul>{paineis.map((p) => <li key={p.id}>Painel {p.numeroPainel} - {p.localizacao} <a href="#" onClick={(e) => { e.preventDefault(); excluirPainel(p.id); }} style={{ color: "red", marginLeft: 8 }}>excluir</a></li>)}</ul>
      <form onSubmit={criarPainel}>
        <input placeholder="Número" value={painelNumero} onChange={(e) => setPainelNumero(e.target.value)} required />
        <input placeholder="Localização" value={painelLocal} onChange={(e) => setPainelLocal(e.target.value)} />
        <button type="submit">Criar Painel</button>
      </form>

      <hr />
      <h3>Estações</h3>
      <ul>{estacoes.map((e) => <li key={e.id}>{nomeExibicao(e)} - {e.localizacao}</li>)}</ul>
      <form onSubmit={criarEstacao}>
        <select value={estacaoTipo} onChange={(e) => setEstacaoTipo(e.target.value)}>
          <option value="MESA">Mesa</option>
          <option value="GUICHE">Guichê</option>
        </select>
        <input placeholder="Número" value={estacaoNumero} onChange={(e) => setEstacaoNumero(e.target.value)} required />
        <input placeholder="Localização" value={estacaoLocal} onChange={(e) => setEstacaoLocal(e.target.value)} />
        <div>
          <label>Painel: </label>
          <select value={estacaoPainelId} onChange={(e) => setEstacaoPainelId(e.target.value)} required>
            <option value="">Selecione</option>
            {paineis.map((p) => <option key={p.id} value={p.id}>Painel {p.numeroPainel}</option>)}
          </select>
        </div>
        <button type="submit">Criar Estação</button>
      </form>

      <h3 style={{ marginTop: 24 }}>Estações vs Painéis</h3>
      <table style={{ width: "100%", borderCollapse: "collapse", marginTop: 8 }}>
        <thead>
          <tr style={{ textAlign: "left", borderBottom: "2px solid #ccc" }}>
            <th style={{ padding: "6px 10px" }}>Estação</th>
            <th style={{ padding: "6px 10px" }}>Localização</th>
            <th style={{ padding: "6px 10px" }}>Painéis</th>
            <th style={{ padding: "6px 10px" }}></th>
          </tr>
        </thead>
        <tbody>
          {estacoes.map((e) => (
            <tr key={e.id} style={{ borderBottom: "1px solid #eee" }}>
              <td style={{ padding: "6px 10px" }}>{nomeExibicao(e)}</td>
              <td style={{ padding: "6px 10px" }}>{e.localizacao}</td>
              <td style={{ padding: "6px 10px" }}>{e.painel ? `Painel ${e.painel.numeroPainel}` : "-"}</td>
              <td style={{ padding: "6px 10px" }}><a href="#" onClick={(ev) => { ev.preventDefault(); excluirEstacao(e.id); }} style={{ color: "red" }}>excluir</a></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
