import React, { useState, useEffect } from "react";
import { api } from "../services/api";

export default function Configuracao() {
  const [paineis, setPaineis] = useState([]);
  const [salas, setSalas] = useState([]);
  const [agenciaId, setAgenciaId] = useState("");
  const [msg, setMsg] = useState("");

  // Painel form
  const [painelNumero, setPainelNumero] = useState("");
  const [painelLocal, setPainelLocal] = useState("");

  // Sala form
  const [salaNome, setSalaNome] = useState("");
  const [salaLocal, setSalaLocal] = useState("");
  const [salaPainelIds, setSalaPainelIds] = useState([]);

  useEffect(() => {
    if (agenciaId) carregarDados();
  }, [agenciaId]);

  async function carregarDados() {
    try {
      setPaineis(await api.get(`/api/admin/painel/${agenciaId}`));
      setSalas(await api.get(`/api/admin/sala/${agenciaId}`));
    } catch (e) {
      setMsg("Erro ao carregar: " + e.message);
    }
  }

  async function criarPainel(e) {
    e.preventDefault();
    try {
      await api.post("/api/admin/painel", { agenciaId, numero: Number(painelNumero), localizacao: painelLocal });
      setPainelNumero(""); setPainelLocal("");
      carregarDados();
      setMsg("Painel criado");
    } catch (err) { setMsg(err.message); }
  }

  async function criarSala(e) {
    e.preventDefault();
    try {
      await api.post("/api/admin/sala", { agenciaId, nome: salaNome, localizacao: salaLocal, painelIds: salaPainelIds });
      setSalaNome(""); setSalaLocal(""); setSalaPainelIds([]);
      carregarDados();
      setMsg("Sala criada");
    } catch (err) { setMsg(err.message); }
  }

  function togglePainel(id) {
    setSalaPainelIds((prev) =>
      prev.includes(id) ? prev.filter((p) => p !== id) : [...prev, id]
    );
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
      <ul>{paineis.map((p) => <li key={p.id}>Painel {p.numero} - {p.localizacao}</li>)}</ul>
      <form onSubmit={criarPainel}>
        <input placeholder="Número" value={painelNumero} onChange={(e) => setPainelNumero(e.target.value)} required />
        <input placeholder="Localização" value={painelLocal} onChange={(e) => setPainelLocal(e.target.value)} />
        <button type="submit">Criar Painel</button>
      </form>

      <hr />
      <h3>Salas</h3>
      <ul>{salas.map((s) => <li key={s.id}>{s.nome} - {s.localizacao}</li>)}</ul>
      <form onSubmit={criarSala}>
        <input placeholder="Nome" value={salaNome} onChange={(e) => setSalaNome(e.target.value)} required />
        <input placeholder="Localização" value={salaLocal} onChange={(e) => setSalaLocal(e.target.value)} />
        <div>
          <label>Painéis:</label>
          {paineis.map((p) => (
            <label key={p.id} style={{ marginLeft: 8 }}>
              <input type="checkbox" checked={salaPainelIds.includes(p.id)} onChange={() => togglePainel(p.id)} />
              {p.numero}
            </label>
          ))}
        </div>
        <button type="submit">Criar Sala</button>
      </form>
    </div>
  );
}
