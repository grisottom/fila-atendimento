import React, { useState } from "react";
import { api } from "../services/api";

export default function Triagem() {
  const [cpf, setCpf] = useState("");
  const [agenciaId, setAgenciaId] = useState("");
  const [servicoId, setServicoId] = useState("");
  const [resultado, setResultado] = useState(null);
  const [erro, setErro] = useState("");

  const servicos = [
    { id: "servico-basico", nome: "Serviço Básico" },
    { id: "servico-normal-01", nome: "Serviço Normal 01" },
    { id: "servico-normal-02", nome: "Serviço Normal 02" },
    { id: "servico-especial-01", nome: "Serviço Especial 01" },
  ];

  async function recepcionar(e) {
    e.preventDefault();
    setErro(""); setResultado(null);
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
          <input value={agenciaId} onChange={(e) => setAgenciaId(e.target.value)} placeholder="agencia-01" required />
        </div>
        <div>
          <label>CPF: </label>
          <input value={cpf} onChange={(e) => setCpf(e.target.value)} placeholder="11122233344" required />
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
    </div>
  );
}
