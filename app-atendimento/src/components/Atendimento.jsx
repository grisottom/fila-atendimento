import React, { useState, useEffect } from "react";
import { api } from "../services/api";

export default function Atendimento() {
  const [salaId, setSalaId] = useState("");
  const [atendimentoAtual, setAtendimentoAtual] = useState(null);
  const [msg, setMsg] = useState("");

  useEffect(() => {
    carregarAtivo();
  }, []);

  async function carregarAtivo() {
    try {
      const res = await api.get("/api/atendimento/ativo");
      if (res) setAtendimentoAtual(res);
    } catch (err) { /* sem atendimento ativo */ }
  }

  async function chamarProximo() {
    setMsg("");
    try {
      const res = await api.post("/api/atendimento/chamar", { salaId: Number(salaId) });
      setAtendimentoAtual(res);
    } catch (err) { setMsg(err.message); }
  }

  async function ausentar() {
    if (!atendimentoAtual) return;
    try {
      await api.post(`/api/atendimento/ausentar/${atendimentoAtual.id}`);
      setAtendimentoAtual(null);
      setMsg("Pessoa ausente - voltou para o fim da fila");
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
    } catch (err) { setMsg(err.message); }
  }

  const emCurso = atendimentoAtual != null;

  return (
    <div>
      <h2>Atendimento</h2>
      <div>
        <label>Sala ID: </label>
        <input value={salaId} onChange={(e) => setSalaId(e.target.value)} placeholder="1" disabled={emCurso} />
        <button onClick={chamarProximo} disabled={!salaId || emCurso}>Chamar Próximo</button>
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
    </div>
  );
}
