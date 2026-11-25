package com.changeimpact.common.config;

import com.changeimpact.common.logging.MdcRequestFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Infrastructure configuration for RabbitMQ messaging and web filters.
 *
 * <p>Queue topology:
 * <pre>
 *   Exchange: changeimpact.main (direct)
 *     → routing key: repository.index → Queue: changeimpact.repository.index
 *         ↘ DLQ: changeimpact.repository.index.dlq
 *     → routing key: analysis.job    → Queue: changeimpact.analysis.job
 *         ↘ DLQ: changeimpact.analysis.job.dlq
 *
 *   Exchange: changeimpact.dead-letter (direct) → receives rejected messages
 * </pre>
 *
 * <p>All queues are durable. Dead-letter queues capture permanently failed
 * messages for manual inspection or automated alerting.
 */
@Configuration
@RequiredArgsConstructor
public class InfrastructureConfig {

    private final AppProperties appProperties;

    // ---- RabbitMQ ----

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new JacksonJsonMessageConverter(); // Jackson 3 (Spring AMQP 4)
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jacksonMessageConverter());
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jacksonMessageConverter());
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        // parallel jobs across repositories; jobs on one repository are serialised by WorkspaceManager's lock
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(Math.max(1, appProperties.getIngestion().getMaxConcurrentJobs()));
        return factory;
    }

    // Exchanges
    @Bean
    public DirectExchange mainExchange() {
        return ExchangeBuilder
                .directExchange(appProperties.getRabbitmq().getExchanges().getMain())
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return ExchangeBuilder
                .directExchange(appProperties.getRabbitmq().getExchanges().getDeadLetter())
                .durable(true)
                .build();
    }

    // Repository Index Queue
    @Bean
    public Queue repositoryIndexQueue() {
        String queueName = appProperties.getRabbitmq().getQueues().getRepositoryIndex();
        String dlqName = appProperties.getRabbitmq().getQueues().getRepositoryIndexDlq();
        String dlxName = appProperties.getRabbitmq().getExchanges().getDeadLetter();
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", dlxName)
                .withArgument("x-dead-letter-routing-key", dlqName)
                .build();
    }

    @Bean
    public Queue repositoryIndexDlq() {
        return QueueBuilder
                .durable(appProperties.getRabbitmq().getQueues().getRepositoryIndexDlq())
                .build();
    }

    @Bean
    public Binding repositoryIndexBinding() {
        return BindingBuilder
                .bind(repositoryIndexQueue())
                .to(mainExchange())
                .with("repository.index");
    }

    @Bean
    public Binding repositoryIndexDlqBinding() {
        return BindingBuilder
                .bind(repositoryIndexDlq())
                .to(deadLetterExchange())
                .with(appProperties.getRabbitmq().getQueues().getRepositoryIndexDlq());
    }

    // Analysis Job Queue
    @Bean
    public Queue analysisJobQueue() {
        String queueName = appProperties.getRabbitmq().getQueues().getAnalysisJob();
        String dlqName = appProperties.getRabbitmq().getQueues().getAnalysisJobDlq();
        String dlxName = appProperties.getRabbitmq().getExchanges().getDeadLetter();
        return QueueBuilder.durable(queueName)
                .withArgument("x-dead-letter-exchange", dlxName)
                .withArgument("x-dead-letter-routing-key", dlqName)
                .build();
    }

    @Bean
    public Queue analysisJobDlq() {
        return QueueBuilder
                .durable(appProperties.getRabbitmq().getQueues().getAnalysisJobDlq())
                .build();
    }

    @Bean
    public Binding analysisJobBinding() {
        return BindingBuilder
                .bind(analysisJobQueue())
                .to(mainExchange())
                .with("analysis.job");
    }

    @Bean
    public Binding analysisJobDlqBinding() {
        return BindingBuilder
                .bind(analysisJobDlq())
                .to(deadLetterExchange())
                .with(appProperties.getRabbitmq().getQueues().getAnalysisJobDlq());
    }

    // ---- MDC Logging Filter ----

    @Bean
    public FilterRegistrationBean<MdcRequestFilter> mdcFilter() {
        FilterRegistrationBean<MdcRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new MdcRequestFilter());
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    // ---- WebClient Customizer (OS-native IPv6/DNS Resolution) ----

    @Bean
    public org.springframework.boot.webclient.WebClientCustomizer webClientCustomizer() {
        return webClientBuilder -> {
            reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                    .resolver(io.netty.resolver.DefaultAddressResolverGroup.INSTANCE)
                    .responseTimeout(java.time.Duration.ofSeconds(15));
            webClientBuilder.clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient));
        };
    }
}

