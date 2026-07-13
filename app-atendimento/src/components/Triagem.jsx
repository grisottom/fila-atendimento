import React, { useState, useEffect, useRef } from "react";
import { api } from "../services/api";
import keycloak from "../services/keycloak";

function getStorageKey() {
  return `app_agencia_${keycloak.tokenParsed?.preferred_username}`;
}

export default function Triagem() {
  const [cpf, setCpf] = useState("");
  const [agenciaId, setAgenciaId] = useState(localStorage.getItem(getStorageKey()) || "");
  const [servicoId, setServicoId] = useState("");
  const [resultado, setResultado] = useState(null);
  const [erro, setErro] = useState("");
  const [agendamentos, setAgendamentos] = useState([]);

  const agenciaRef = useRef(null);
  const cpfRef = useRef(null);

  useEffect(() => {
    if (!agenciaId) {
      agenciaRef.current?.focus();
    } else {
      cpfRef.current?.focus();
      carregarAgendamentos();
    }
  }, []);

  useEffect(() => {
    if (agenciaId) carregarAgendamentos();
  }, [agenciaId]);

  async function carregarAgendamentos() {
    try {
      const res = await api.get(`/api/triagem/agendamentos/${agenciaId}`);
      setAgendamentos(res || []);
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
        cpf: Number(cpf), agenciaId, servicoId,
      });
      setResultado(res);
    } catch (err) {
      setErro(err.message);
    }
  }

  return (
    <div>
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
          <label>Serviço: </label>
          <select value={servicoId} onChange={(e) => setServicoId(e.target.value)} required>
            <option value="">Selecione</option>
            {servicos.map((s) => <option key={s.id} value={s.id}>{s.nome}</option>)}
          </select>
        </div>
        <button type="submit">Recepcionar</button>
      </form>

      {erro && <p style={{ color: "red" }}>{erro}</p>}
      {resultado && (
        <div style={{ marginTop: 16, padding: 16, border: "1px solid green" }}>
          <h3>Senha: {resultado.senha}</h3>
          <p>Nome: {resultado.nomePessoa}</p>
          <p>Serviço: {resultado.servicoId}</p>
          {resultado.horarioAgendado && <p>Agendado para: {new Date(resultado.horarioAgendado).toLocaleTimeString()}</p>}
        </div>
      )}

      {agendamentos.length > 0 && (
        <div style={{ marginTop: 32 }}>
          <h3>Agendamentos do dia — {agenciaId}</h3>
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
                    <a href="#" onClick={(e) => { e.preventDefault(); setCpf(String(a.cpf)); setServicoId(a.servicoId); }} style={{ color: "#1976d2", cursor: "pointer" }}>{a.cpf}</a>
                  </td>
                  <td style={{ padding: "6px 10px" }}>{a.nomePessoa}</td>
                  <td style={{ padding: "6px 10px" }}>{a.servicoId}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
