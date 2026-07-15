import React, { useState, useEffect, useRef } from "react";
import { api } from "../services/api";
import keycloak from "../services/keycloak";

function getStorageKey() {
  return `app_agencia_${keycloak.tokenParsed?.preferred_username}`;
}

const HISTORICO_KEY = "triagem_historico";

function carregarHistorico() {
  try {
    const raw = localStorage.getItem(HISTORICO_KEY);
    if (!raw) return [];
    const { data, items } = JSON.parse(raw);
    if (data !== new Date().toISOString().slice(0, 10)) {
      localStorage.removeItem(HISTORICO_KEY);
      return [];
    }
    return items;
  } catch { return []; }
}

function salvarHistorico(items) {
  localStorage.setItem(HISTORICO_KEY, JSON.stringify({
    data: new Date().toISOString().slice(0, 10),
    items,
  }));
}

export default function Triagem() {
  const [cpf, setCpf] = useState("");
  const [nomePessoa, setNomePessoa] = useState("");
  const [agenciaId, setAgenciaId] = useState(localStorage.getItem(getStorageKey()) || "");
  const [servicoId, setServicoId] = useState("");
  const [resultado, setResultado] = useState(null);
  const [erro, setErro] = useState("");
  const [agendamentos, setAgendamentos] = useState([]);
  const [agendPagina, setAgendPagina] = useState(0);
  const [agendTotalPaginas, setAgendTotalPaginas] = useState(0);
  const [agendTotal, setAgendTotal] = useState(0);
  const [historico, setHistorico] = useState(carregarHistorico);
  const [histPagina, setHistPagina] = useState(0);

  const agenciaRef = useRef(null);
  const cpfRef = useRef(null);
  const dialogRef = useRef(null);

  useEffect(() => {
    if (!agenciaId) agenciaRef.current?.focus();
    else { cpfRef.current?.focus(); carregarAgendamentos(); }
  }, []);

  useEffect(() => {
    if (agenciaId) carregarAgendamentos();
  }, [agenciaId]);

  async function carregarAgendamentos(page = agendPagina) {
    try {
      const res = await api.get(`/api/triagem/agendamentos/${agenciaId}?page=${page}&size=5`);
      setAgendamentos(res.content || []);
      setAgendTotalPaginas(res.totalPages || 0);
      setAgendTotal(res.totalElements || 0);
      setAgendPagina(page);
    } catch (e) { /* ignora */ }
  }

  const servicos = [
    { id: "servico-basico", nome: "Serviço Básico" },
    { id: "servico-normal-01", nome: "Serviço Normal 01" },
    { id: "servico-normal-02", nome: "Serviço Normal 02" },
    { id: "servico-especial-01", nome: "Serviço Especial 01" },
  ];

  async function recepcionar(e) {
    e.preventDefault();
    setErro(""); setResultado(null);
    localStorage.setItem(getStorageKey(), agenciaId);
    try {
      const res = await api.post("/api/triagem/recepcionar", {
        cpf: Number(cpf), nomePessoa, agenciaId, servicoId,
      });
      setResultado(res);
      const entry = { ...res, hora: new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" }) };
      const novo = [entry, ...historico];
      setHistorico(novo);
      salvarHistorico(novo);
      dialogRef.current?.showModal();
      carregarAgendamentos(0);
    } catch (err) {
      setErro(err.message);
    }
  }

  function imprimir() {
    window.print();
  }

  function fecharDialog() {
    dialogRef.current?.close();
    setCpf(""); setNomePessoa(""); setServicoId("");
    cpfRef.current?.focus();
    setHistorico(carregarHistorico());
  }

  function abrirSenha(entry) {
    setResultado(entry);
    dialogRef.current?.showModal();
  }

  return (
    <div>
      <style>{`
        @media print {
          body * { visibility: hidden; }
          #senha-card, #senha-card * { visibility: visible; }
          #senha-card { position: absolute; top: 0; left: 0; width: 100%; }
        }
        dialog::backdrop { background: rgba(0,0,0,0.4); }
        dialog { border: 1px solid #ccc; border-radius: 8px; padding: 24px; max-width: 360px; }
      `}</style>

      <h2>Triagem</h2>
      <form onSubmit={recepcionar}>
        <div>
          <label>Agência: </label>
          <input ref={agenciaRef} value={agenciaId} onChange={(e) => setAgenciaId(e.target.value)} placeholder="agencia-01" required />
        </div>
        <div>
          <label>CPF: </label>
          <input ref={cpfRef} value={cpf} onChange={(e) => setCpf(e.target.value)} placeholder="11122233344" required />
        </div>
        <div>
          <label>Nome: </label>
          <input value={nomePessoa} onChange={(e) => setNomePessoa(e.target.value)} placeholder="Nome da pessoa" style={{ minWidth: 200 }} required />
        </div>
        <div>
          <label>Serviço: </label>
          <select value={servicoId} onChange={(e) => setServicoId(e.target.value)} required>
            <option value="">Selecione</option>
            {servicos.map((s) => <option key={s.id} value={s.id}>{s.nome}</option>)}
          </select>
        </div>
        <button type="submit">Recepcionar</button>
      </form>

      {erro && <p style={{ color: "red" }}>{erro}</p>}

      <dialog ref={dialogRef}>
        {resultado && (
          <div id="senha-card" style={{ textAlign: "center" }}>
            <h2 style={{ margin: "0 0 8px" }}>Senha</h2>
            <h1 style={{ fontSize: 48, margin: "0 0 12px" }}>{resultado.senha}</h1>
            <p style={{ margin: 4 }}>{resultado.nomePessoa}</p>
            <p style={{ margin: 4 }}>{servicos.find(s => s.id === resultado.servicoId)?.nome || resultado.servicoId}</p>
            {resultado.horarioAgendado && <p style={{ margin: 4 }}>Agendado: {new Date(resultado.horarioAgendado).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</p>}
            <p style={{ margin: "12px 0 0", fontSize: 12, color: "#666" }}>{new Date().toLocaleDateString("pt-BR")} — {new Date().toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</p>
          </div>
        )}
        <div style={{ marginTop: 16, textAlign: "center" }}>
          <button onClick={imprimir} style={{ marginRight: 8 }}>Imprimir</button>
          <button onClick={fecharDialog}>Fechar</button>
        </div>
      </dialog>

      {historico.length > 0 && (
        <div style={{ marginTop: 32 }}>
          <h3>Senhas geradas hoje ({historico.length})</h3>
          <table style={{ width: "100%", borderCollapse: "collapse", marginTop: 8 }}>
            <thead>
              <tr style={{ textAlign: "left", borderBottom: "2px solid #ccc" }}>
                <th style={{ padding: "6px 10px" }}>Hora</th>
                <th style={{ padding: "6px 10px" }}>Senha</th>
                <th style={{ padding: "6px 10px" }}>Nome</th>
                <th style={{ padding: "6px 10px" }}>Serviço</th>
              </tr>
            </thead>
            <tbody>
              {historico.slice(histPagina * 5, histPagina * 5 + 5).map((h, i) => (
                <tr key={i} style={{ borderBottom: "1px solid #eee" }}>
                  <td style={{ padding: "6px 10px" }}>{h.hora}</td>
                  <td style={{ padding: "6px 10px" }}>
                    <a href="#" onClick={(e) => { e.preventDefault(); abrirSenha(h); }} style={{ color: "#1976d2", cursor: "pointer" }}>{h.senha}</a>
                  </td>
                  <td style={{ padding: "6px 10px" }}>{h.nomePessoa}</td>
                  <td style={{ padding: "6px 10px" }}>{servicos.find(s => s.id === h.servicoId)?.nome || h.servicoId}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {Math.ceil(historico.length / 5) > 1 && (
            <div style={{ marginTop: 8 }}>
              <button onClick={() => setHistPagina(p => p - 1)} disabled={histPagina === 0}>Anterior</button>
              <span style={{ margin: "0 12px" }}>Página {histPagina + 1} de {Math.ceil(historico.length / 5)}</span>
              <button onClick={() => setHistPagina(p => p + 1)} disabled={(histPagina + 1) * 5 >= historico.length}>Próxima</button>
            </div>
          )}
        </div>
      )}

      {(agendamentos.length > 0 || agendTotal > 0) && (
        <div style={{ marginTop: 32 }}>
          <h3>Agendamentos do dia — {agenciaId} ({agendTotal})</h3>
          <table style={{ width: "100%", borderCollapse: "collapse", marginTop: 8 }}>
            <thead>
              <tr style={{ textAlign: "left", borderBottom: "2px solid #ccc" }}>
                <th style={{ padding: "6px 10px" }}>Horário</th>
                <th style={{ padding: "6px 10px" }}>CPF</th>
                <th style={{ padding: "6px 10px" }}>Nome</th>
                <th style={{ padding: "6px 10px" }}>Serviço</th>
              </tr>
            </thead>
            <tbody>
              {agendamentos.map((a, i) => (
                <tr key={i} style={{ borderBottom: "1px solid #eee" }}>
                  <td style={{ padding: "6px 10px" }}>{new Date(a.dataHora).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}</td>
                  <td style={{ padding: "6px 10px" }}>
                    <a href="#" onClick={(e) => { e.preventDefault(); setCpf(String(a.cpf)); setNomePessoa(a.nomePessoa); setServicoId(a.servicoId); }} style={{ color: "#1976d2", cursor: "pointer" }}>{a.cpf}</a>
                  </td>
                  <td style={{ padding: "6px 10px" }}>{a.nomePessoa}</td>
                  <td style={{ padding: "6px 10px" }}>{a.servicoId}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {agendTotalPaginas > 1 && (
            <div style={{ marginTop: 8 }}>
              <button onClick={() => carregarAgendamentos(agendPagina - 1)} disabled={agendPagina === 0}>Anterior</button>
              <span style={{ margin: "0 12px" }}>Página {agendPagina + 1} de {agendTotalPaginas}</span>
              <button onClick={() => carregarAgendamentos(agendPagina + 1)} disabled={agendPagina + 1 >= agendTotalPaginas}>Próxima</button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
