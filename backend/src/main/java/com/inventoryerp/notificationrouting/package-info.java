/**
 * Notification routing (ADR-S065): when a catalogued event happens, the people responsible for the record it concerns
 * (assignee, salesperson, requester, manager, ...) are told, plus any roles or users the organization adds, over the
 * channels it picks. Spec: docs/spec/notification-routing.md.
 *
 * <p>This module depends on {@code kernel} only. Each owning module implements {@code notificationrouting.spi
 * .RoutedEventSource} and declares its events, their record lookup and their parties, so the router reads another
 * module's table only through SQL that module wrote.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Notification routing")
package com.inventoryerp.notificationrouting;
