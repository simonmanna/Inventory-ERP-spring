/**
 * Application services of the manufacturing module, exposed as the {@code application} named interface so the read-only
 * analytics module can call the report services (ADR-S020). Writes from other modules stay on the module events.
 */
@org.springframework.modulith.NamedInterface("application")
package com.inventoryerp.manufacturing.application;
