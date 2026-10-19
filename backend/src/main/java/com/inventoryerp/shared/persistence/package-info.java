/**
 * Persistence helpers shared by every module: {@link DbEnum}, tenancy filter definitions.
 */
@FilterDef(name = TenancyFilters.TENANT, parameters = @ParamDef(name = TenancyFilters.TENANT_PARAM, type = String.class))
@FilterDef(name = TenancyFilters.SOFT_DELETE)
package com.inventoryerp.shared.persistence;

import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;
