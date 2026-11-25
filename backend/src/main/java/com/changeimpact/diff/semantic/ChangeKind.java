package com.changeimpact.diff.semantic;

/**
 * Semantic change classification. {@code severity} (0..1) feeds the risk heuristic's "change severity"
 * factor; {@code contractChange} marks kinds that alter how other code may use the symbol.
 */
public enum ChangeKind {
    ADDED(0.10, false, "New symbol"),
    REMOVED(1.00, true, "Symbol removed"),
    MOVED(0.90, true, "Type moved/renamed to another package or file"),
    PARAMETERS_CHANGED(0.90, true, "Parameter list changed (overload replaced)"),
    /** Languages without overloading: optional parameters added, every existing call still works. */
    PARAMETERS_EXTENDED(0.40, false, "Optional parameters added (existing calls still work)"),
    /** Languages with optional type hints: only the annotations of the parameters changed. */
    PARAMETER_ANNOTATIONS_CHANGED(0.30, false, "Parameter type hints changed (calls are unaffected at runtime)"),
    RETURN_TYPE_CHANGED(0.90, true, "Return type changed"),
    FIELD_TYPE_CHANGED(0.85, true, "Field type changed"),
    VISIBILITY_REDUCED(0.80, true, "Visibility reduced"),
    VISIBILITY_EXPANDED(0.30, false, "Visibility expanded"),
    MODIFIERS_CHANGED(0.60, false, "static/final/abstract modifiers changed"),
    TYPE_HIERARCHY_CHANGED(0.80, false, "extends/implements changed"),
    DECLARATION_CHANGED(0.50, false, "Type declaration changed (e.g. type parameters)"),
    REST_MAPPING_CHANGED(0.85, true, "REST endpoint mapping changed"),
    SECURITY_RULE_CHANGED(0.90, false, "Security annotation changed"),
    ENTITY_MAPPING_CHANGED(0.75, false, "JPA entity/relationship mapping changed"),
    TRANSACTION_BOUNDARY_CHANGED(0.70, false, "@Transactional boundary changed"),
    CONFIG_BINDING_CHANGED(0.60, false, "Configuration binding annotation changed"),
    CONFIGURATION_VALUE_CHANGED(0.50, false, "A configuration property bound by this symbol changed"),
    ANNOTATIONS_CHANGED(0.40, false, "Other annotations changed"),
    BODY_CHANGED(0.45, false, "Implementation changed (signature unchanged)");

    private final double severity;
    private final boolean contractChange;
    private final String description;

    ChangeKind(double severity, boolean contractChange, String description) {
        this.severity = severity;
        this.contractChange = contractChange;
        this.description = description;
    }

    public double getSeverity() {
        return severity;
    }

    public boolean isContractChange() {
        return contractChange;
    }

    public String getDescription() {
        return description;
    }
}
