/**
 * Credit management (ADR-S011 §3, docs/spec/credit.md): customer credit limits, the organization credit policy,
 * exposure (undelivered orders, delivered-not-invoiced, receivables, deposits, unapplied credits) and immutable credit
 * decisions at check points. Other modules contribute exposure through {@code CreditExposureContributor}; this module
 * depends on none of them.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Credit")
package com.inventoryerp.credit;
