/**
 * Licensing: this installation's licence, verified offline, and the gate that makes it mean something.
 *
 * <p>{@link com.inventoryerp.kernel.licensing.LicenseService} is the entry point everything else
 * uses; {@link com.inventoryerp.kernel.licensing.LicenseStateMachine} decides the mode and
 * {@link com.inventoryerp.kernel.licensing.LicenseEnforcementInterceptor} applies it. The licence
 * drives the module gating that already exists rather than adding another
 * ({@link com.inventoryerp.kernel.licensing.LicenseEntitlements}).
 *
 * @see <a href="file:../../../../../../../docs/spec/licensing.md">docs/spec/licensing.md</a>
 */
@org.springframework.modulith.NamedInterface("licensing")
package com.inventoryerp.kernel.licensing;
