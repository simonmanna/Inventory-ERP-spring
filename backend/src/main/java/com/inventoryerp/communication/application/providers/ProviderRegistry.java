package com.inventoryerp.communication.application.providers;

import com.inventoryerp.shared.web.ApiException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Resolves a {@code providerId} to its adapter (port of {@code provider-registry.service.ts} +
 * {@code provider-bootstrap.service.ts}). Internal is always registered; WhatsApp on
 * {@code ENABLE_COMMUNICATION_WHATSAPP=true} (Cloud API transport), Telegram on
 * {@code ENABLE_COMMUNICATION_TELEGRAM=true}. {@code WHATSAPP_TRANSPORT=baileys} has no Java port and is rejected at
 * startup (docs/spec/communication.md, Deviations).
 */
@Component
@ConditionalOnProperty(name = "app.modules.communication", havingValue = "true")
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<String, MessagingProvider> providers = new LinkedHashMap<>();

    public ProviderRegistry(InternalProvider internal, WhatsAppCloudProvider whatsapp, TelegramProvider telegram,
            @Value("${ENABLE_COMMUNICATION_WHATSAPP:false}") boolean whatsappEnabled,
            @Value("${WHATSAPP_TRANSPORT:cloud}") String whatsappTransport,
            @Value("${ENABLE_COMMUNICATION_TELEGRAM:false}") boolean telegramEnabled) {
        register(internal);
        if (whatsappEnabled) {
            if ("baileys".equals(whatsappTransport)) {
                throw new IllegalStateException("WHATSAPP_TRANSPORT=baileys is not supported by the Spring backend"
                        + " (no Java port of Baileys). Use WHATSAPP_TRANSPORT=cloud (WhatsApp Cloud API).");
            }
            register(whatsapp);
            log.info("WhatsApp provider registered (transport={})", whatsappTransport);
        }
        if (telegramEnabled) {
            register(telegram);
            log.info("Telegram provider registered");
        }
    }

    public final synchronized void register(MessagingProvider provider) {
        providers.put(provider.id(), provider);
    }

    /** Remove a provider (tests toggle the inner flags at runtime). */
    public synchronized void unregister(String providerId) {
        if (!"internal".equals(providerId)) {
            providers.remove(providerId);
        }
    }

    /** Throws 404 if the provider is not registered (its flag is off). */
    public synchronized MessagingProvider forId(String providerId) {
        MessagingProvider p = providers.get(providerId);
        if (p == null) {
            throw ApiException.notFound("Communication provider '" + providerId + "' is not enabled on this server.");
        }
        return p;
    }

    public synchronized boolean has(String providerId) {
        return providers.containsKey(providerId);
    }

    public synchronized List<MessagingProvider> list() {
        return new ArrayList<>(providers.values());
    }
}
