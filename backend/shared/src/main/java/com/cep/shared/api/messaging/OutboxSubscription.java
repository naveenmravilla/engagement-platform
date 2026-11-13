package com.cep.shared.api.messaging;

/**
 * Declares that messages on {@code topic} are delivered to the job queue {@code queue}. Register as a bean;
 * the job payload is the message payload and the job tenant is the message tenant.
 */
public record OutboxSubscription(String topic, String queue) {}
