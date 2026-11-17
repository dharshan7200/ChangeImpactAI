package com.changeimpact.graph.algorithms;

import com.changeimpact.graph.model.CouplingMetrics;
import com.changeimpact.graph.model.GraphEdge;
import com.changeimpact.graph.model.GraphNode;
import org.jgrapht.Graph;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Calculates Robert C. Martin's instability and coupling metrics ($Ca, Ce, I$)
 * to uncover architectural hotspots and high blast-radius symbols.
 */
@Component
public class CouplingMetricsCalculator {

    public List<CouplingMetrics> calculateMetrics(Graph<GraphNode, GraphEdge> graph) {
        List<CouplingMetrics> metricsList = new ArrayList<>();

        for (GraphNode node : graph.vertexSet()) {
            int ca = graph.inDegreeOf(node);  // Afferent coupling: incoming dependents
            int ce = graph.outDegreeOf(node); // Efferent coupling: outgoing dependencies

            double instability = 0.0;
            if (ca + ce > 0) {
                instability = (double) ce / (ca + ce);
            }

            boolean isHotspot = (ca >= 5) || (ce >= 10);

            metricsList.add(CouplingMetrics.builder()
                    .symbolId(node.getId())
                    .qualifiedName(node.getQualifiedName())
                    .symbolType(node.getSymbolType())
                    .afferentCoupling(ca)
                    .efferentCoupling(ce)
                    .instability(Math.round(instability * 100.0) / 100.0)
                    .isHotspot(isHotspot)
                    .build());
        }

        // Sort by afferent coupling descending (most heavily depended-upon first)
        metricsList.sort(Comparator.comparingInt(CouplingMetrics::getAfferentCoupling).reversed());
        return metricsList;
    }
}

