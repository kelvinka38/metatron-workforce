package com.metatron.workforce.workplace;

/**
 * Channel transport boundary. The adapter consumes an already-authorized Workplace delivery;
 * it does not own Objective identity, Conversation identity or institutional authorization.
 */
public interface WorkplaceDeliveryAdapter {
    boolean supports(String channel);
    String deliver(WorkplaceDelivery delivery);
}
