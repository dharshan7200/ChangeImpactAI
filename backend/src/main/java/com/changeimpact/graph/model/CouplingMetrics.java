package com.changeimpact.graph.model;

import com.changeimpact.parser.model.SymbolType;
import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * Structural coupling metrics calculated for an individual symbol.
 *
 * <p>Metrics definitions:
 * <ul>
 *   <li><b>Afferent Coupling ($Ca$)</b>: Number of other classes/methods that depend on this symbol (incoming edges).</li>
 *   <li><b>Efferent Coupling ($Ce$)</b>: Number of other classes/methods this symbol depends upon (outgoing edges).</li>
 *   <li><b>Instability ($I$)</b>: $I = \frac{Ce}{Ca + Ce}$. Ranges from 0 (completely stable, max dependents) to 1 (completely instable, depends on everything).</li>
 *   <li><b>Hotspot</b>: High blast-radius component with $Ca \ge 5$ or $Ce \ge 10$.</li>
 * </ul>
 */
@Getter
@Builder
public class CouplingMetrics {
    private final UUID symbolId;
    private final String qualifiedName;
    private final SymbolType symbolType;
    private final int afferentCoupling;
    private final int efferentCoupling;
    private final double instability;
    private final boolean isHotspot;
}

