import React, { useState, useEffect } from "react";
import { api } from "../services/api";
import keycloak from "../services/keycloak";

function getStorageKey() {
  return `app_agencia_${keycloak.tokenParsed?.preferred_username}`;
}

function getAgenciaDoToken() {
  const ag = keycloak.tokenParsed?.agencia;
  if (Array.isArray(ag)) return ag[0] || "";
  return ag || "";
}

export default function Configuracao() {
  const [paineis, setPaineis] = useState([]);
  const [estacoes, setEstacoes] = useState([]);
  const [servicos, setServicos] = useState([]);
  const [paineisServicos, setPaineisServicos] = useState({});
  const [agenciaId, setAgenciaId] = useState(localStorage.getItem(getStorageKey()) || getAgenciaDoToken());
  const [msg, setMsg] = useState("");

  // Painel form
  const [painelNumero, setPainelNumero] = useState("");
  const [painelLocal, setPainelLocal] = useState("");

  // Estacao form
  const [estacaoTipo, setEstacaoTipo] = useState("MESA");
  const [estacaoNumero, setEstacaoNumero] = useState("");
  const [estacaoLocal, setEstacaoLocal] = useState("");

  // Painel-Servico form
  const [psServicoId, setPsServicoId] = useState("");
  const [psPainelId, setPsPainelId] = useState("");

  useEffect(() => {
    if (agenciaId) carregarDados();
  }, [agenciaId]);

  async function carregarDados() {
    localStorage.setItem(getStorageKey(), agenciaId);
    try {
      const [p, e, s] = await Promise.all([
        api.get(`/api/admin/painel/${agenciaId}`),
        api.get(`/api/admin/estacao/${agenciaId}`),
        api.get("/api/admin/servicos"),
      ]);
      setPaineis(p || []);
      setEstacoes(e || []);
      setServicos(s || []);

      // Carrega associações painel ↔ serviço
      const psMap = {};
      for (const painel of (p || [])) {
        const ps = await api.get(`/api/admin/paineis-servicos/${painel.id}`);
        psMap[painel.id] = ps || [];
      }
      setPaineisServicos(psMap);
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
      await api.post("/api/admin/estacao", { agenciaId, tipoEstacao: estacaoTipo, numeroEstacao: Number(estacaoNumero), localizacao: estacaoLocal });
      setEstacaoTipo("MESA"); setEstacaoNumero(""); setEstacaoLocal("");
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

  async function associarServicoAoPainel(e) {
    e.preventDefault();
    if (!psPainelId || !psServicoId) return;
    try {
      await api.post("/api/admin/paineis-servicos", { painelId: Number(psPainelId), servicoId: psServicoId });
      setPsServicoId(""); setPsPainelId("");
      carregarDados();
      setMsg("Serviço associado ao painel");
    } catch (err) { setMsg(err.message); }
  }

  async function desassociarServicoDoPainel(painelId, servicoId) {
    try {
      await api.delete(`/api/admin/paineis-servicos/${painelId}/${servicoId}`);
      carregarDados();
      setMsg("Serviço desassociado do painel");
    } catch (err) { setMsg(err.message); }
  }

  function nomeExibicao(e) {
    const tipo = e.tipoEstacao === "GUICHE" ? "Guichê" : e.tipoEstacao === "SALA" ? "Sala" : "Mesa";
    return tipo + " " + e.numeroEstacao;
  }

  return (
    <div>
      <h2>Configuração da Agência</h2>
      <label>Agência ID: </label>
      <input value={agenciaId} readOnly placeholder="ex: agencia-01" style={{ background: "#f0f0f0" }} />
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
      <ul>{estacoes.map((e) => <li key={e.id}>{nomeExibicao(e)} - {e.localizacao} <a href="#" onClick={(ev) => { ev.preventDefault(); excluirEstacao(e.id); }} style={{ color: "red", marginLeft: 8 }}>excluir</a></li>)}</ul>
      <form onSubmit={criarEstacao}>
        <select value={estacaoTipo} onChange={(e) => setEstacaoTipo(e.target.value)}>
          <option value="MESA">Mesa</option>
          <option value="GUICHE">Guichê</option>
          <option value="SALA">Sala</option>
        </select>
        <input placeholder="Número" value={estacaoNumero} onChange={(e) => setEstacaoNumero(e.target.value)} required />
        <input placeholder="Localização" value={estacaoLocal} onChange={(e) => setEstacaoLocal(e.target.value)} />
        <button type="submit">Criar Estação</button>
      </form>

      <hr />
      <h3>Painéis × Serviços</h3>
      <table style={{ width: "100%", borderCollapse: "collapse", marginTop: 8 }}>
        <thead>
          <tr style={{ textAlign: "left", borderBottom: "2px solid #ccc" }}>
            <th style={{ padding: "6px 10px" }}>Painel</th>
            <th style={{ padding: "6px 10px" }}>Serviços associados</th>
          </tr>
        </thead>
        <tbody>
          {paineis.map((p) => (
            <tr key={p.id} style={{ borderBottom: "1px solid #eee" }}>
              <td style={{ padding: "6px 10px" }}>Painel {p.numeroPainel} ({p.localizacao})</td>
              <td style={{ padding: "6px 10px" }}>
                {(paineisServicos[p.id] || []).length === 0
                  ? <span style={{ color: "#999" }}>Nenhum serviço</span>
                  : (paineisServicos[p.id] || []).map((ps) => (
                    <span key={ps.id} style={{ marginRight: 12 }}>
                      {ps.servicoId}
                      <a href="#" onClick={(e) => { e.preventDefault(); desassociarServicoDoPainel(p.id, ps.servicoId); }} style={{ color: "red", marginLeft: 4 }}>×</a>
                    </span>
                  ))
                }
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <form onSubmit={associarServicoAoPainel} style={{ marginTop: 8 }}>
        <select value={psPainelId} onChange={(e) => setPsPainelId(e.target.value)} required>
          <option value="">Painel...</option>
          {paineis.map((p) => <option key={p.id} value={p.id}>Painel {p.numeroPainel}</option>)}
        </select>
        <select value={psServicoId} onChange={(e) => setPsServicoId(e.target.value)} required style={{ marginLeft: 8 }}>
          <option value="">Serviço...</option>
          {servicos.map((s) => <option key={s.id} value={s.id}>{s.nome || s.id}</option>)}
        </select>
        <button type="submit" style={{ marginLeft: 8 }}>Associar</button>
      </form>
    </div>
  );
}
