package com.infomedia.abacox.telephonypricing.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.ClassMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EVENTS_EXCHANGE = "abacox.events";
    public static final String QUERIES_EXCHANGE = "abacox.queries";

    public static final String TELEPHONY_QUERIES_QUEUE = "abacox.telephony-pricing.queries";
    public static final String TELEPHONY_EVENTS_QUEUE = "abacox.telephony-pricing.events";

    @Bean
    public TopicExchange eventsExchange() {
        return new TopicExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange queriesExchange() {
        return new DirectExchange(QUERIES_EXCHANGE, true, false);
    }

    @Bean
    public Queue telephonyQueriesQueue() {
        return new Queue(TELEPHONY_QUERIES_QUEUE, true);
    }

    @Bean
    public Queue telephonyEventsQueue() {
        return new Queue(TELEPHONY_EVENTS_QUEUE, true);
    }

    @Bean
    public Binding telephonyQueriesBinding(Queue telephonyQueriesQueue, DirectExchange queriesExchange) {
        return BindingBuilder.bind(telephonyQueriesQueue).to(queriesExchange).with("telephony-pricing");
    }

    @Bean
    public Binding telephonyEventsBinding(Queue telephonyEventsQueue, TopicExchange eventsExchange) {
        return BindingBuilder.bind(telephonyEventsQueue).to(eventsExchange).with("#");
    }

    @Bean
    public Jackson2JsonMessageConverter messageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        converter.setClassMapper(new ClassMapper() {
            @Override
            public void fromClass(Class<?> clazz, MessageProperties properties) {
                properties.setHeader("__TypeId__", "InternalMessage");
            }
            @Override
            public Class<?> toClass(MessageProperties properties) {
                return InternalMessage.class;
            }
        });
        return converter;
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        return template;
    }

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        return factory;
    }
}
