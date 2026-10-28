package com.inventoryerp.core.application;

import java.util.Optional;

/**
 * The approved inventory costing method for a product scope (ADR-S007). Implemented by the {@code inventory} module's
 * cost policy; {@link ProductService} uses it so a product's {@code costingMethod} follows the active policy instead
 * of being a free per-product toggle. No implementation (or no active policy) = legacy behaviour.
 */
public interface ProductCostingPolicy {

    /** Method of the active policy covering {@code categoryId} (category policy first, then organisation). */
    Optional<String> methodFor(String categoryId);
}
