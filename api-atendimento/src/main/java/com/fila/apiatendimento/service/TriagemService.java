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
    private static final java.util.concurrent.atomic.AtomicInteger faultCountTriagem = new java.util.concurrent.atomic.AtomicInteger(0);

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

        // UUID de correlação gerado AQUI, antes do insert. É ele que viaja na
        // mensagem do broker, então não dependemos da PK (IDENTITY) para publicar.
        java.util.UUID triagemUuid = java.util.UUID.randomUUID();
        fila.setTriagemUuid(triagemUuid);

        // Outbox transacional com UMA única escrita: publicamos primeiro (o UUID já
        // existe em memória) e definimos a flag conforme o resultado. O save único
        // ao final persiste o estado final — não há insert seguido de update.
        //   - publish OK    -> publicadoNoBroker=true  (caminho feliz)
        //   - publish falha -> publicadoNoBroker=false; o OutboxPublisher republica.
        try {
            String servicoId = request.servicoId();
            String permissao = servicoId != null
                    ? servicoRepository.findById(servicoId).map(s -> s.getPermissaoExigida()).orElse("basica")
                    : "basica";

            // FAULT INJECTION (publish): desativada — simulava falha ao publicar no broker.
            // if (faultCountTriagem.incrementAndGet() <= 1) {
            //     log.warn("[FAULT] Simulando falha ao publicar no broker (item #{}) para triagemUuid={}", faultCountTriagem.get(), triagemUuid);
            //     throw new RuntimeException("[FAULT] Falha ao publicar no broker");
            // } else {
            //     publicarNaQueueAgencia(request.agenciaId(), triagemUuid, permissao, agendamento != null);
            // }
            publicarNaQueueAgencia(request.agenciaId(), triagemUuid, permissao, agendamento != null);
            fila.setPublicadoNoBroker(true);
        } catch (Exception e) {
            fila.setPublicadoNoBroker(false);
            log.warn("Falha ao publicar no broker (outbox vai republicar): {}", e.getMessage());
        }

        // Única persistência: grava a linha já com o estado final da flag.
        filaRepository.save(fila);

        if (agendamento != null) {
            agendamentoRepository.delete(agendamento);
        }

        // FAULT INJECTION (commit): desativada. Simulava o cenário de falha de commit
        // após o publish. O teste empírico mostrou que, com sessionTransacted=true no
        // jmsQueueTemplate, a sessão JMS é sincronizada com a transação e sofre rollback
        // junto — ou seja, NÃO gera mensagem órfã (publish e persistência caem juntos).
        // if (faultCountTriagem.incrementAndGet() <= 2) {
        //     log.warn("[FAULT] Simulando falha de COMMIT (item #{}) para triagemUuid={} — publish já feito, forçando rollback",
        //             faultCountTriagem.get(), triagemUuid);
        //     throw new RuntimeException("[FAULT] Falha de commit simulada (mensagem órfã no broker)");
        // }

        return new TriagemResponse(senha, nomePessoa, request.servicoId(),
                agendamento != null ? agendamento.getDataHora() : null);
    }

    /**
     * Republica manualmente um atendimento AGUARDANDO na fila do broker.
     *
     * Rede de segurança operacional: se a mensagem de um atendimento se perdeu no
     * broker (ex.: reinício do broker sem armazenamento persistente), a linha
     * permanece AGUARDANDO no banco mas não há mensagem na fila para o atendente
     * consumir. Esta ação, acionada pelo triador, recoloca a mensagem na fila.
     *
     * NÃO altera o status nem qualquer outro dado — apenas republica. Se a mensagem
     * ainda estiver viva na fila (uso indevido), o chamarProximo é idempotente:
     * ao consumir a duplicata, verá o status já diferente de AGUARDANDO e a descarta.
     */
    @Transactional(readOnly = true)
    public void republicar(Integer id) {
        FilaAtendimento fila = filaRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Atendimento não encontrado: " + id));

        if (!"AGUARDANDO".equals(fila.getStatus())) {
            throw new IllegalArgumentException(
                    "Só é possível republicar atendimentos AGUARDANDO (status atual: " + fila.getStatus() + ")");
        }

        String permissao = fila.getServicoId() != null
                ? servicoRepository.findById(fila.getServicoId()).map(s -> s.getPermissaoExigida()).orElse("basica")
                : "basica";

        publicarNaQueueAgencia(fila.getAgenciaId(), fila.getTriagemUuid(), permissao, fila.getHorarioAgendado() != null);
        log.info("Republicação manual: filaId={} triagemUuid={} recolocado na fila da agência {}",
                fila.getId(), fila.getTriagemUuid(), fila.getAgenciaId());
    }

    void publicarNaQueueAgencia(String agenciaId, java.util.UUID triagemUuid,
                                            String permissao, boolean agendado) {
        String queueAgencia = "agencia." + agenciaId + ".fila";
        int prioridade = agendado ? 9 : 4;
        String uuidStr = triagemUuid.toString();

        jmsQueueTemplate.send(queueAgencia, session -> {
            Message message = session.createTextMessage(uuidStr);
            message.setStringProperty("triagemUuid", uuidStr);
            message.setStringProperty("permissao", permissao);
            message.setJMSDeliveryMode(DeliveryMode.PERSISTENT);
            message.setJMSPriority(prioridade);
            return message;
        });

        log.info("Publicado na fila {}: triagemUuid={}, permissao={}, prioridade={}",
                queueAgencia, uuidStr, permissao, prioridade);
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
                        f.getServicoId(), f.getStatus(), null, null, f.getHorarioChegada()))
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
