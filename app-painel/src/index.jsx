import React from "react";
import ReactDOM from "react-dom/client";
import App from "./App";
import keycloak from "./services/keycloak";

keycloak.init({ onLoad: "login-required", checkLoginIframe: false }).then((authenticated) => {
  if (authenticated) {
    const root = ReactDOM.createRoot(document.getElementById("root"));
    root.render(<React.StrictMode><App /></React.StrictMode>);
  }
});
