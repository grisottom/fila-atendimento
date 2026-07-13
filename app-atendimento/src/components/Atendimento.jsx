import React, { useState, useEffect } from "react";
import { api } from "../services/api";
import keycloak from "../services/keycloak";

function getAgenciaKey() {
  return `app_agencia_${keycloak.tokenParsed?.preferred_username}`;
}

function getSalaKey() {
  return `atend_sala_${keycloak.tokenParsed?.preferred_username}`;
}

export default function Atendimento() {
  const [agenciaId, setAgenciaId] = useState(localStorage.getItem(getAgenciaKey()) || "");
  const [salaId, setSalaId] = useState(localStorage.getItem(getSalaKey()) || "");
  const [atendimentoAtual, setAtendimentoAtual] = useState(null);
  const [msg, setMsg] = useState("");
  const [servicos, setServicos] = useState([]);
  const [fila, setFila] = useState([]);

  useEffect(() => {
    carregarAtivo();
    carregarServicos();
  }, []);

  useEffect(() => {
    if (agenciaId) {
      localStorage.setItem(getAgenciaKey(), agenciaId);
      carregarFila();
    }
  }, [agenciaId]);

  async function carregarAtivo() {
    try {
      const res = await api.get("/api/atendimento/ativo");
      if (res) setAtendimentoAtual(res);
    } catch (err) { /* sem atendimento ativo */ }
  }

  async function carregarServicos() {
    try {
      const res = await api.get("/api/atendimento/meus-servicos");
      setServicos(res || []);
    } catch (err) { /* ignora */ }
  }

  async function carregarFila() {
    try {
      const res = await api.get(`/api/atendimento/fila-disponivel?agenciaId=${agenciaId}`);
      setFila(res || []);
    } catch (err) { /* ignora */ }
  }

  async function chamarProximo() {
    setMsg("");
    localStorage.setItem(getSalaKey(), salaId);
    localStorage.setItem(getAgenciaKey(), agenciaId);
    try {
      const res = await api.post("/api/atendimento/chamar", { salaId: Number(salaId) });
      setAtendimentoAtual(res);
      carregarFila();
    } catch (err) { setMsg(err.message); }
  }

  async function ausentar() {
    if (!atendimentoAtual) return;
    try {
      await api.post(`/api/atendimento/ausentar/${atendimentoAtual.id}`);
      setAtendimentoAtual(null);
      setMsg("Pessoa ausente - voltou para o fim da fila");
      carregarFila();
    } catch (err) { setMsg(err.message); }
  }

  async function chamarNovamente() {
    if (!atendimentoAtual) return;
    setMsg("");
    try {
      await api.post(`/api/atendimento/rechamar/${atendimentoAtual.id}`);
      setMsg("Chamada repetida no painel");
    } catch (err) { setMsg(err.message); }
  }

  async function iniciar() {
    if (!atendimentoAtual) return;
    try {
      const res = await api.post(`/api/atendimento/iniciar/${atendimentoAtual.id}`);
      setAtendimentoAtual(res);
    } catch (err) { setMsg(err.message); }
  }

  async function finalizar() {
    if (!atendimentoAtual) return;
    try {
      await api.post(`/api/atendimento/finalizar/${atendimentoAtual.id}`);
      setAtendimentoAtual(null);
      setMsg("Atendimento finalizado");
      carregarFila();
    } catch (err) { setMsg(err.message); }
  }

  const emCurso = atendimentoAtual != null;

  return (
    <div>
      <h2>Atendimento</h2>
      <div>
        <label>Agência: </label>
        <input value={agenciaId} onChange={(e) => setAgenciaId(e.target.value)} placeholder="agencia-01" disabled={emCurso} />
        <label style={{ marginLeft: 16 }}>Sala ID: </label>
        <input value={salaId} onChange={(e) => setSalaId(e.target.value)} placeholder="1" disabled={emCurso} />
        <button onClick={chamarProximo} disabled={!salaId || !agenciaId || emCurso} style={{ marginLeft: 8 }}>Chamar Próximo</button>
      </div>

      {msg && <p style={{ color: "blue" }}>{msg}</p>}

      {atendimentoAtual && (
        <div style={{ marginTop: 16, padding: 16, border: "1px solid #333" }}>
          <h3>Senha: {atendimentoAtual.senha}</h3>
          <p>Nome: {atendimentoAtual.nomePessoa}</p>
          <p>Serviço: {atendimentoAtual.servicoId}</p>
          <p>Sala: {atendimentoAtual.sala}</p>
          <p>Status: {atendimentoAtual.status}</p>
          <div style={{ marginTop: 8 }}>
            {atendimentoAtual.status === "CHAMANDO" && (
              <>
                <button onClick={chamarNovamente} style={{ marginRight: 8 }}>Chamar Novamente</button>
                <button onClick={ausentar} style={{ marginRight: 8 }}>Ausente</button>
                <button onClick={iniciar}>Iniciar Atendimento</button>
              </>
            )}
            {atendimentoAtual.status === "EM_ATENDIMENTO" && (
              <button onClick={finalizar}>Finalizar Atendimento</button>
            )}
          </div>
        </div>
      )}

      <div style={{ marginTop: 32 }}>
        <h3>Meus Serviços</h3>
        {servicos.length === 0 ? <p>Nenhum serviço associado</p> : (
          <ul>
            {servicos.map((s) => <li key={s.id}>{s.nome} ({s.permissaoExigida})</li>)}
          </ul>
        )}
      </div>

      <div style={{ marginTop: 24 }}>
        <h3>Fila Disponível ({fila.length})</h3>
        {fila.length === 0 ? <p>Nenhum atendimento aguardando</p> : (
          <table style={{ width: "100%", borderCollapse: "collapse", marginTop: 8 }}>
            <thead>
              <tr style={{ textAlign: "left", borderBottom: "2px solid #ccc" }}>
                <th style={{ padding: "6px 10px" }}>Posição</th>
                <th style={{ padding: "6px 10px" }}>Senha</th>
                <th style={{ padding: "6px 10px" }}>Nome</th>
                <th style={{ padding: "6px 10px" }}>Serviço</th>
              </tr>
            </thead>
            <tbody>
              {fila.map((f, i) => (
                <tr key={f.id} style={{ borderBottom: "1px solid #eee" }}>
                  <td style={{ padding: "6px 10px" }}>{i + 1}</td>
                  <td style={{ padding: "6px 10px", fontWeight: "bold" }}>{f.senha}</td>
                  <td style={{ padding: "6px 10px" }}>{f.nomePessoa}</td>
                  <td style={{ padding: "6px 10px" }}>{f.servicoId}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}
