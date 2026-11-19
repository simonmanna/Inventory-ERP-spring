/**
 * Guide (ADR-S079): in-app guided tours that teach users the ERP on its real screens. The tours are defined in the web
 * client; this module only keeps each user's learning progress. It reads and writes nothing of any business module.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Guide")
package com.inventoryerp.guide;
