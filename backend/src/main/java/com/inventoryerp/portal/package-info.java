/**
 * Customer portal (PORTAL-1, ENABLE_PORTAL): a customer's own orders, deliveries with live trip status, invoices and
 * statement, and the staff screens that open its logins. New module of the construction-materials build (no NestJS
 * counterpart).
 *
 * <p>Design: docs/adr/ADR-S050-customer-portal.md, spec: docs/spec/portal.md. A portal login is an ordinary
 * {@code User} whose only permission is {@code portal:customer}; {@code PortalAccount} ties it to one {@code Partner},
 * and every query here is scoped by that partner, never by a request parameter. This module only reads other modules'
 * tables and calls their {@code application} APIs.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Customer portal")
package com.inventoryerp.portal;
