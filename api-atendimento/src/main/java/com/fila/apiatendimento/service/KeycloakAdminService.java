package com.fila.apiatendimento.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class KeycloakAdminService {

    private static final Logger log = LoggerFactory.getLogger(KeycloakAdminService.class);

    @Value("${app.keycloak.admin-url:http://keycloak:8080}")
    private String keycloakUrl;

    @Value("${app.keycloak.realm:fila-atendimento}")
    private String realm;

    @Value("${app.keycloak.admin-user:admin}")
    private String adminUser;

    @Value("${app.keycloak.admin-password:admin}")
    private String adminPassword;

    private final RestTemplate restTemplate = new RestTemplate();

    private String obterToken() {
        String url = keycloakUrl + "/realms/master/protocol/openid-connect/token";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "password");
        body.add("client_id", "admin-cli");
        body.add("username", adminUser);
        body.add("password", adminPassword);

        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        return (String) response.getBody().get("access_token");
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listarAtendentes(String agenciaId) {
        String token = obterToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);

        String usersUrl = keycloakUrl + "/admin/realms/" + realm + "/users?max=100";
        ResponseEntity<List> usersResponse = restTemplate.exchange(usersUrl, HttpMethod.GET, new HttpEntity<>(headers), List.class);
        List<Map<String, Object>> users = usersResponse.getBody();

        List<Map<String, Object>> resultado = new ArrayList<>();
        for (Map<String, Object> user : users) {
            Map<String, Object> attrs = (Map<String, Object>) user.get("attributes");
            if (attrs == null) continue;
            List<String> agencias = (List<String>) attrs.get("agencia");
            if (agencias == null || !agencias.contains(agenciaId)) continue;

            String userId = (String) user.get("id");
            String username = (String) user.get("username");

            // Buscar roles do usuário
            String rolesUrl = keycloakUrl + "/admin/realms/" + realm + "/users/" + userId + "/role-mappings/realm";
            ResponseEntity<List> rolesResponse = restTemplate.exchange(rolesUrl, HttpMethod.GET, new HttpEntity<>(headers), List.class);
            List<Map<String, Object>> roles = rolesResponse.getBody();

            List<String> roleNames = new ArrayList<>();
            if (roles != null) {
                for (Map<String, Object> role : roles) {
                    String name = (String) role.get("name");
                    if (List.of("basica", "normal", "especial", "admin").contains(name)) {
                        roleNames.add(name);
                    }
                }
            }

            resultado.add(Map.of("username", username, "roles", roleNames));
        }
        return resultado;
    }
}
