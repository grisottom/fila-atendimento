package com.fila.apiatendimento.service;

import com.fila.apiatendimento.dto.AgendamentoResponse;
import com.fila.apiatendimento.dto.AtendimentoResponse;
import com.fila.apiatendimento.dto.TriagemRequest;
import com.fila.apiatendimento.dto.TriagemResponse;
import com.fila.apiatendimento.entity.Agendamento;
import com.fila.apiatendimento.entity.FilaAtendimento;
import com.fila.apiatendimento.entity.Pessoa;
import com.fila.apiatendimento.repository.AgendamentoRepository;
import com.fila.apiatendimento.repository.FilaAtendimentoRepository;
import com.fila.apiatendimento.repository.PessoaRepository;
import com.fila.apiatendimento.repository.ServicoRepository;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class TriagemService {

    private static final Logger log = LoggerFactory.getLogger(TriagemService.class);
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    // FAULT INJECTION: contador para falhar nos 3 primeiros itens
    // private static final java.util.concurrent.atomic.AtomicInteger faultCountTriagem = new java.util.concurrent.atomic.AtomicInteger(0);

    private final PessoaRepository pessoaRepository;
    private final AgendamentoRepository agendamentoRepository;
    private final FilaAtendimentoRepository filaRepository;
    private final ServicoRepository servicoRepository;
    private final JmsTemplate jmsQueueTemplate;

    public TriagemService(PessoaRepository pessoaRepository,
                          AgendamentoRepository agendamentoRepository,
                          FilaAtendimentoRepository filaRepository,
                          ServicoRepository servicoRepository,
                          @Qualifier("jmsQueueTemplate") JmsTemplate jmsQueueTemplate) {
        this.pessoaRepository = pessoaRepository;
        this.agendamentoRepository = agendamentoRepository;
        this.filaRepository = filaRepository;
        this.servicoRepository = servicoRepository;
        this.jmsQueueTemplate = jmsQueueTemplate;
    }

    @Transactional
    public TriagemResponse recepcionar(TriagemRequest request) {
        Long cpf = Objects.requireNonNull(request.cpf(), "CPF não pode ser nulo");

        String nomePessoa = pessoaRepository.findById(cpf)
                .map(p -> p.getNome())
                .orElse(request.nomePessoa());

        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        LocalDateTime fimDia = inicioDia.plusDays(1);

        List<Agendamento> agendamentos = agendamentoRepository
                .findByCpfAndAgenciaIdAndDataHoraBetween(request.cpf(), request.agenciaId(), inicioDia, fimDia);

        Agendamento agendamento = agendamentos.stream()
                .filter(a -> a.getServicoId().equals(request.servicoId()))
                .findFirst().orElse(null);

        String senha = gerarSenha();
        Integer posicao = filaRepository.findMaxPosicaoFila(request.agenciaId()) + 1;

        FilaAtendimento fila = new FilaAtendimento();
        fila.setAgenciaId(request.agenciaId());
        fila.setCpf(request.cpf());
        fila.setNomePessoa(nomePessoa);
        fila.setServicoId(request.servicoId());
        fila.setSenha(senha);
        fila.setHorarioAgendado(agendamento != null ? agendamento.getDataHora() : null);
        fila.setHorarioChegada(LocalDateTime.now());
        fila.setStatus("AGUARDANDO");
        fila.setPosicaoFila(posicao);
        fila.setPublicadoNoBroker(true);
        filaRepository.save(fila);

        // Publica na fila do broker (best effort; outbox republica se falhar)
        try {
            String servicoId = request.servicoId();
            String permissao = servicoId != null
                    ? servicoRepository.findById(servicoId).map(s -> s.getPermissaoExigida()).orElse("basica")
                    : "basica";

            // FAULT INJECTION: simula falha ao publicar no broker no 1º item
            // if (faultCountTriagem.incrementAndGet() <= 1) {
            //     log.warn("[FAULT] Simulando falha ao publicar no broker (item #{}) para filaId={}", faultCountTriagem.get(), fila.getId());
            //     throw new RuntimeException("[FAULT] Falha ao publicar no broker");
            // } else {
            //     publicarNaQueueAgencia(request.agenciaId(), fila.getId(), permissao, agendamento != null);
            // }
            publicarNaQueueAgencia(request.agenciaId(), fila.getId(), permissao, agendamento != null);
        } catch (Exception e) {
            log.warn("Falha ao publicar no broker (outbox vai republicar): {}", e.getMessage());
            fila.setPublicadoNoBroker(false);
            filaRepository.save(fila);
        }

        if (agendamento != null) {
            agendamentoRepository.delete(agendamento);
        }

        return new TriagemResponse(senha, nomePessoa, request.servicoId(),
                agendamento != null ? agendamento.getDataHora() : null);
    }

    void publicarNaQueueAgencia(String agenciaId, Integer filaAtendimentoId,
                                            String permissao, boolean agendado) {
        String queueAgencia = "agencia." + agenciaId + ".fila";
        int prioridade = agendado ? 9 : 4;

        jmsQueueTemplate.send(queueAgencia, session -> {
            Message message = session.createTextMessage(String.valueOf(filaAtendimentoId));
            message.setIntProperty("filaAtendimentoId", filaAtendimentoId);
            message.setStringProperty("permissao", permissao);
            message.setJMSDeliveryMode(DeliveryMode.PERSISTENT);
            message.setJMSPriority(prioridade);
            return message;
        });

        log.info("Publicado na fila {}: filaAtendimentoId={}, permissao={}, prioridade={}",
                queueAgencia, filaAtendimentoId, permissao, prioridade);
    }

    private String gerarSenha() {
        StringBuilder sb = new StringBuilder(5);
        for (int i = 0; i < 5; i++) {
            sb.append(CHARS.charAt(ThreadLocalRandom.current().nextInt(CHARS.length())));
        }
        return sb.toString();
    }

    public Page<AgendamentoResponse> listarAgendamentosDoDia(String agenciaId, int page, int size) {
        LocalDateTime inicioDia = LocalDate.now().atStartOfDay();
        LocalDateTime fimDia = inicioDia.plusDays(1);

        return agendamentoRepository.findByAgenciaIdAndDataHoraBetweenOrderByDataHoraAsc(
                agenciaId, inicioDia, fimDia, PageRequest.of(page, size))
                .map(a -> {
                    String nome = pessoaRepository.findById(a.getCpf())
                            .map(Pessoa::getNome).orElse("Desconhecido");
                    return new AgendamentoResponse(a.getCpf(), nome, a.getServicoId(), a.getDataHora());
                });
    }

    public List<AtendimentoResponse> listarAtendimentosDoDiaEsperando(String agenciaId) {
        return filaRepository.findByAgenciaIdAndStatusIn(agenciaId, List.of("AGUARDANDO", "CANCELADO", "AUSENTE"))
                .stream()
                .map(f -> new AtendimentoResponse(f.getId(), f.getSenha(), f.getCpf(), f.getNomePessoa(),
                        f.getServicoId(), f.getStatus(), null))
                .toList();
    }

    @Transactional
    public TriagemResponse atualizarServico(Integer id, String servicoId) {
        FilaAtendimento fila = filaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Atendimento não encontrado: " + id));
        fila.setServicoId(servicoId);
        fila.setStatus("AGUARDANDO");
        fila.setPublicadoNoBroker(false);
        filaRepository.save(fila);

        return new TriagemResponse(fila.getSenha(), fila.getNomePessoa(), servicoId, fila.getHorarioAgendado());
    }
}
