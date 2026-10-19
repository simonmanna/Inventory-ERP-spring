/**
 * Shared kernel types usable by every module: persistence helpers, cross-module enums, web contract
 * types (pagination, error body). Must not depend on any business module.
 */
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.inventoryerp.shared;
