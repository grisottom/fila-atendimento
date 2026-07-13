package com.fila.apiatendimento.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fila.apiatendimento.dto.AtendimentoResponse;
import com.fila.apiatendimento.entity.FilaAtendimento;
import com.fila.apiatendimento.entity.Painel;
import com.fila.apiatendimento.entity.Sala;
import com.fila.apiatendimento.entity.Servico;
import com.fila.apiatendimento.repository.FilaAtendimentoRepository;
import com.fila.apiatendimento.repository.SalaRepository;
import com.fila.apiatendimento.repository.ServicoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class AtendimentoService {

    private static final Logger log = LoggerFactory.getLogger(AtendimentoService.class);

    private final FilaAtendimentoRepository filaRepository;
    private final SalaRepository salaRepository;
    private final ServicoRepository servicoRepository;
    private final JmsTemplate jmsTemplate;
    private final ObjectMapper objectMapper;

    public AtendimentoService(FilaAtendimentoRepository filaRepository,
                              SalaRepository salaRepository,
                              ServicoRepository servicoRepository,
                              JmsTemplate jmsTemplate,
                              ObjectMapper objectMapper) {
        this.filaRepository = filaRepository;
        this.salaRepository = salaRepository;
        this.servicoRepository = servicoRepository;
        this.jmsTemplate = jmsTemplate;
        this.objectMapper = objectMapper;
    }

    public AtendimentoResponse buscarAtivo(String username) {
        return filaRepository.findFirstByAtendenteUsernameAndStatusInOrderByHorarioChamadaDesc(username, List.of("CHAMANDO", "EM_ATENDIMENTO"))
                .map(fila -> {
                    String salaNome = fila.getSalaId() != null
                            ? salaRepository.findById(fila.getSalaId()).map(s -> s.getNome()).orElse(null)
                            : null;
                    return toResponse(fila, salaNome);
                })
                .orElse(null);
    }

    @Transactional
    public AtendimentoResponse chamarProximo(Integer salaId, String username, List<String> permissoes) {
        Sala sala = salaRepository.findById(salaId)
                .orElseThrow(() -> new RuntimeException("Sala não encontrada: " + salaId));

        if (sala.getPaineis() == null || sala.getPaineis().isEmpty()) {
            throw new RuntimeException("Sala sem painéis associados");
        }

        List<FilaAtendimento> emChamada = filaRepository.findByAgenciaIdAndStatusIn(
                sala.getAgenciaId(), List.of("CHAMANDO"));
        emChamada.stream()
                .filter(f -> username.equals(f.getAtendenteUsername()))
                .forEach(f -> { /* atendente só pode ter 1 chamada ativa */ });

        FilaAtendimento proximo = filaRepository.findProximoParaAtendimento(sala.getAgenciaId(), permissoes)
                .orElseThrow(() -> new RuntimeException("Nenhum atendimento na fila"));

        proximo.setStatus("CHAMANDO");
        proximo.setSalaId(salaId);
        proximo.setAtendenteUsername(username);
        proximo.setHorarioChamada(LocalDateTime.now());
        filaRepository.save(proximo);

        publicarNoPainel(sala, proximo, "CHAMANDO");

        return toResponse(proximo, sala.getNome());
    }

    @Transactional
    public void rechamar(@NonNull Integer atendimentoId) {
        FilaAtendimento fila = filaRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        Sala sala = salaRepository.findById(fila.getSalaId())
                .orElseThrow(() -> new RuntimeException("Sala não encontrada"));

        publicarNoPainel(sala, fila, "CHAMANDO");
    }

    @Transactional
    public AtendimentoResponse ausentar(@NonNull Integer atendimentoId) {
        FilaAtendimento fila = filaRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        Sala sala = salaRepository.findById(fila.getSalaId()).orElse(null);

        Integer maxPosicao = filaRepository.findMaxPosicaoFila(fila.getAgenciaId()) + 1;
        fila.setStatus("AGUARDANDO");
        fila.setPosicaoFila(maxPosicao);
        fila.setHorarioAgendado(null);
        fila.setSalaId(null);
        fila.setAtendenteUsername(null);
        fila.setHorarioChamada(null);
        filaRepository.save(fila);

        if (sala != null) {
            publicarNoPainel(sala, fila, "AUSENTE");
        }

        return toResponse(fila, sala != null ? sala.getNome() : null);
    }

    @Transactional
    public AtendimentoResponse iniciarAtendimento(@NonNull Integer atendimentoId) {
        FilaAtendimento fila = filaRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        fila.setStatus("EM_ATENDIMENTO");
        fila.setHorarioInicioAtendimento(LocalDateTime.now());
        filaRepository.save(fila);

        Sala sala = salaRepository.findById(fila.getSalaId()).orElse(null);
        if (sala != null) {
            publicarNoPainel(sala, fila, "EM_ATENDIMENTO");
        }

        return toResponse(fila, sala != null ? sala.getNome() : null);
    }

    @Transactional
    public AtendimentoResponse finalizarAtendimento(@NonNull Integer atendimentoId) {
        FilaAtendimento fila = filaRepository.findById(atendimentoId)
                .orElseThrow(() -> new RuntimeException("Atendimento não encontrado"));

        Sala sala = fila.getSalaId() != null ? salaRepository.findById(fila.getSalaId()).orElse(null) : null;

        fila.setStatus("FINALIZADO");
        fila.setHorarioFimAtendimento(LocalDateTime.now());
        filaRepository.save(fila);

        if (sala != null) {
            publicarNoPainel(sala, fila, "FINALIZADO");
        }

        return toResponse(fila, sala != null ? sala.getNome() : null);
    }

    private void publicarNoPainel(Sala sala, FilaAtendimento fila, String status) {
        for (Painel painel : sala.getPaineis()) {
            try {
                String topico = "agencia." + sala.getAgenciaId() + ".painel." + painel.getNumero();
                String json = objectMapper.writeValueAsString(Map.of(
                        "agenciaId", sala.getAgenciaId(),
                        "painelId", painel.getNumero(),
                        "senha", fila.getSenha(),
                        "nomePessoa", fila.getNomePessoa(),
                        "sala", sala.getNome(),
                        "status", status
                ));
                jmsTemplate.send(topico, session -> session.createTextMessage(json));
            } catch (Exception e) {
                log.error("Erro ao publicar no painel {}: {}", painel.getNumero(), e.getMessage());
            }
        }
    }

    private AtendimentoResponse toResponse(FilaAtendimento fila, String salaNome) {
        return new AtendimentoResponse(fila.getId(), fila.getSenha(), fila.getNomePessoa(),
                fila.getServicoId(), fila.getStatus(), salaNome);
    }

    public List<Servico> listarServicosPorPermissoes(List<String> permissoes) {
        return servicoRepository.findByPermissaoExigidaIn(permissoes);
    }

    public List<AtendimentoResponse> listarFilaDisponivel(String agenciaId, List<String> permissoes) {
        if (agenciaId == null || permissoes.isEmpty()) return List.of();
        return filaRepository.findFilaDisponivel(agenciaId, permissoes)
                .stream()
                .map(f -> toResponse(f, null))
                .toList();
    }
}
