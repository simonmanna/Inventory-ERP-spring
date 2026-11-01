/**
 * Projects and contract commercials (Cost Management CM-8a / CM-8b, ADR-S038 / ADR-S039). New module (no NestJS
 * counterpart): projects, sites, WBS, budgets, cost-to-complete forecasts and the project cost sub-ledger; contract
 * variations, progress claims, retentions, advances, subcontracts, IFRS 15 revenue recognition and IAS 37 onerous
 * contract assessments. Posts only through the accounting posting engine; moves stock only through the inventory
 * stock engine.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Projects")
package com.inventoryerp.projects;
