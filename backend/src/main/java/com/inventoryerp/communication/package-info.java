/**
 * Conversations, messages, channels (Telegram, WhatsApp Cloud), SSE stream (ENABLE_COMMUNICATION). Port of apps/api/src/modules/communication.
 *
 * <p>Spec: docs/spec/communication.md. Entities in {@code domain} are exposed so other modules may read them;
 * writes to another module's tables go through that module's application services or events.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Communication")
package com.inventoryerp.communication;
