import React, { useState, useEffect, useRef } from "react";
import useSWR from "swr";
import { api } from "../services/api";
import keycloak from "../services/keycloak";

function getAgenciaKey() {
  return `app_agencia_${keycloak.tokenParsed?.preferred_username}`;
}

function getEstacaoKey() {
  return `atend_estacao_${keycloak.tokenParsed?.preferred_username}`;
}

function getEstacaoTipoKey() {
  return `atend_estacao_tipo_${keycloak.tokenParsed?.preferred_username}`;
}

function getEstacaoNumeroKey() {
  return `atend_estacao_numero_${keycloak.tokenParsed?.preferred_username}`;
}

export default function Atendimento() {
  const [agenciaId, setAgenciaId] = useState(localStorage.getItem(getAgenciaKey()) || "");
  const [estacaoId, setEstacaoId] = useState("");
  const [estacaoTipo, setEstacaoTipo] = useState(localStorage.getItem(getEstacaoTipoKey()) || "MESA");
  const [estacaoNumero, setEstacaoNumero] = useState(localStorage.getItem(getEstacaoNumeroKey()) || "");
  const [atendimentoAtual, setAtendimentoAtual] = useState(null);
  const [msg, setMsg] = useState("");
  const [servicos, setServicos] = useState([]);

  const btnIniciarRef = useRef(null);

  const { data: fila = [], mutate: mutarFila } = useSWR(
    agenciaId ? `/api/atendimento/fila-disponivel?agenciaId=${agenciaId}` : null,
    (url) => api.get(url),
    { refreshInterval: 5000 }
  );

  useEffect(() => {
    carregarAtivo();
    carregarServicos();
    btnIniciarRef.current?.focus();
  }, []);

  useEffect(() => {
    if (agenciaId) {
      localStorage.setItem(getAgenciaKey(), agenciaId);
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

  async function iniciarEstacao() {
    setMsg("");
    try {
      const estacoes = await api.get(`/api/atendimento/estacoes/${agenciaId}`);
      const encontrada = estacoes.find((e) => e.tipoEstacao === estacaoTipo && e.numeroEstacao === Number(estacaoNumero));
      if (!encontrada) {
        setMsg(`Estação ${{ GUICHE: "Guichê", SALA: "Sala" }[estacaoTipo] ?? "Mesa"} ${estacaoNumero} não encontrada`);
        return;
      }
      setEstacaoId(String(encontrada.id));
      localStorage.setItem(getEstacaoKey(), String(encontrada.id));
      localStorage.setItem(getEstacaoTipoKey(), estacaoTipo);
      localStorage.setItem(getEstacaoNumeroKey(), estacaoNumero);
      localStorage.setItem(getAgenciaKey(), agenciaId);
      setMsg(`Estação iniciada: ${{ GUICHE: "Guichê", SALA: "Sala" }[estacaoTipo] ?? "Mesa"} ${estacaoNumero}`);
    } catch (err) { setMsg(err.message); }
  }

  async function chamarProximo() {
    setMsg("");
    localStorage.setItem(getEstacaoKey(), estacaoId);
    localStorage.setItem(getAgenciaKey(), agenciaId);
    try {
      const res = await api.post("/api/atendimento/chamar", { estacaoId: Number(estacaoId) });
      setAtendimentoAtual(res);
      mutarFila();
    } catch (err) { setMsg(err.message); }
  }

  async function ausentar() {
    if (!atendimentoAtual) return;
    try {
      await api.post(`/api/atendimento/ausentar/${atendimentoAtual.id}`);
      setAtendimentoAtual(null);
      setMsg("Pessoa ausente - voltou para o fim da fila");
      mutarFila();
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
      mutarFila();
    } catch (err) { setMsg(err.message); }
  }

  async function cancelar() {
    if (!atendimentoAtual) return;
    try {
      await api.post(`/api/atendimento/cancelar/${atendimentoAtual.id}`);
      setAtendimentoAtual(null);
      setMsg("Atendimento para fila com status CANCELADO, pode ser recepcionado novamente");
      mutarFila();
    } catch (err) { setMsg(err.message); }
  }

  const emCurso = atendimentoAtual != null;

  return (
    <div>
      <h2>Atendimento</h2>
      <div>
        <label>Agência: </label>
        <input value={agenciaId} onChange={(e) => setAgenciaId(e.target.value)} placeholder="agencia-01" disabled={!!estacaoId || emCurso} />
      </div>
      <div style={{ marginTop: 8 }}>
        <label>Tipo: </label>
        <select value={estacaoTipo} onChange={(e) => setEstacaoTipo(e.target.value)} disabled={!!estacaoId || emCurso}>
          <option value="MESA">Mesa</option>
          <option value="GUICHE">Guichê</option>
          <option value="SALA">Sala</option>
        </select>
        <label style={{ marginLeft: 8 }}>Número: </label>
        <input value={estacaoNumero} onChange={(e) => setEstacaoNumero(e.target.value)} placeholder="1" style={{ width: 50 }} disabled={!!estacaoId || emCurso} />
        <button ref={btnIniciarRef} onClick={iniciarEstacao} disabled={!agenciaId || !estacaoNumero || emCurso} style={{ marginLeft: 8 }}>Iniciar Estação</button>
      </div>
      <div style={{ marginTop: 8 }}>
        <label>Estação: </label>
        <input value={estacaoId} disabled style={{ width: 40 }} />
        <button onClick={chamarProximo} disabled={!estacaoId || emCurso} style={{ marginLeft: 8 }}>Chamar Próximo</button>
      </div>

      {msg && <p style={{ color: "blue" }}>{msg}</p>}

      <div style={{ display: "flex", gap: 24, marginTop: 16, alignItems: "flex-start" }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          {atendimentoAtual ? (
            <div style={{ padding: 16, border: "1px solid #333" }}>
              <h3>Senha: {atendimentoAtual.senha}</h3>
              <p>Nome: {atendimentoAtual.nomePessoa}</p>
              <p>Serviço: {atendimentoAtual.servicoId}</p>
              <p>Estação: {atendimentoAtual.estacao}</p>
              <p>Status: {atendimentoAtual.status}</p>
              <div style={{ marginTop: 8 }}>
                {atendimentoAtual.status === "CHAMANDO" && (
                  <>
                    <button onClick={ausentar} style={{ marginRight: 8 }}>Ausente</button>
                    <button onClick={iniciar} style={{ marginRight: 8 }}>Iniciar Atendimento</button>
                    <button onClick={cancelar} style={{ color: "red" }}>Cancelar</button>
                  </>
                )}
                {atendimentoAtual.status === "EM_ATENDIMENTO" && (
                  <>
                    <button onClick={finalizar} style={{ marginRight: 8 }}>Finalizar Atendimento</button>
                    <button onClick={cancelar} style={{ color: "red" }}>Cancelar</button>
                  </>
                )}
              </div>
            </div>
          ) : (
            <p style={{ opacity: 0.5 }}>Nenhum atendimento em curso</p>
          )}
        </div>

        <div style={{ flex: 1, minWidth: 0 }}>
          <div>
            <h3>Meus Serviços</h3>
            {servicos.length === 0 ? <p>Nenhum serviço associado</p> : (
              <ul>
                {servicos.map((s) => <li key={s.id}>{s.nome} ({s.permissaoExigida})</li>)}
              </ul>
            )}
          </div>

          <div style={{ marginTop: 24 }}>
            <h3>Fila Aguardando ({fila.length})</h3>
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
      </div>
    </div>
  );
}
