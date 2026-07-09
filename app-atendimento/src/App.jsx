import React from "react";
import { BrowserRouter, Routes, Route, NavLink } from "react-router-dom";
import keycloak from "./services/keycloak";
import Configuracao from "./components/Configuracao";
import Triagem from "./components/Triagem";
import Atendimento from "./components/Atendimento";

function hasRole(role) {
  return keycloak.hasRealmRole(role);
}

export default function App() {
  const isAdmin = hasRole("admin");

  return (
    <BrowserRouter>
      <nav style={{ padding: 12, borderBottom: "1px solid #ccc", display: "flex", gap: 16 }}>
        <strong>Atendimento</strong>
        {isAdmin && <NavLink to="/configuracao">Configuração</NavLink>}
        {(isAdmin || hasRole("basica")) && <NavLink to="/triagem">Triagem</NavLink>}
        <NavLink to="/atendimento">Atendimento</NavLink>
        <span style={{ marginLeft: "auto" }}>
          {keycloak.tokenParsed?.preferred_username}
          {" | "}
          <button onClick={() => keycloak.logout()}>Sair</button>
        </span>
      </nav>
      <div style={{ padding: 16 }}>
        <Routes>
          {isAdmin && <Route path="/configuracao" element={<Configuracao />} />}
          <Route path="/triagem" element={<Triagem />} />
          <Route path="/atendimento" element={<Atendimento />} />
          <Route path="*" element={<Atendimento />} />
        </Routes>
      </div>
    </BrowserRouter>
  );
}
