package com.changeimpact.architecture.twin;

import java.time.Instant;
import java.util.List;

/** Data of the architecture digital twin (snapshots, hotspots, comparisons). */
public final class TwinModel {

    private TwinModel() {
    }

    /**
     * One production package of a commit. Coupling follows Robert C. Martin: Ca = packages depending on this one,
     * Ce = packages this one depends on, I = Ce / (Ca + Ce), A = abstract types / types, D = |A + I − 1| (distance from
     * the main sequence; 0 is balanced, near (0,0) is the "zone of pain", near (1,1) the "zone of uselessness").
     *
     * @param cycleGroup index into {@link Snapshot#cycles()}, or -1 when the package is in no package cycle
     * @param order      position in a dependency-respecting order (dependencies first); used to lay out the matrix
     */
    public record PackageNode(String name, int files, int types, int abstractTypes, int methods, long loc, long complexity,
                              int ca, int ce, double instability, double abstractness, double distance,
                              int cycleGroup, int order) {
    }

    /** Dependencies from one package to another: references (calls, field types, …) and distinct file pairs. */
    public record PackageEdge(String source, String target, long references, long filePairs, boolean inCycle) {
    }

    public record Summary(int packages, int files, int types, int methods, long loc, long complexity,
                          int packageEdges, long crossPackageReferences, int cycles, int packagesInCycles,
                          double averageInstability, double averageDistance, int violations) {
    }

    public record Snapshot(String commitSha, Instant commitDate, String commitMessage, Summary summary,
                           List<PackageNode> packages, List<PackageEdge> edges, List<List<String>> cycles) {
    }

    /** A point of the trend: the summary of one analysed commit. */
    public record TrendPoint(String commitSha, Instant commitDate, String commitMessage, Summary summary) {
    }

    /**
     * One production file with its change history and complexity. {@code churnCommits}/{@code authors} count the
     * scanned history up to the snapshot's commit; {@code fanIn} = other files depending on it; {@code coverage} is the
     * line coverage of the commit's latest JaCoCo report (null without one). {@code hotspotScore} = churn percentile ×
     * complexity percentile (0..1); a hotspot is a file in the top quarter of both with at least two changes.
     */
    public record FileHotspot(String path, String packageName, String primaryType, long loc, int types, int methods,
                              long complexity, int maxMethodComplexity, int churnCommits, long churnLines, int authors,
                              Instant lastChanged, int fanIn, Double coverage, double hotspotScore, boolean hotspot) {
    }

    public record Hotspots(String commitSha, int historyCommits, boolean coverageAvailable, List<FileHotspot> files) {
    }

    public record PackageDelta(String name, String status, long locBefore, long locAfter, int caBefore, int caAfter,
                               int ceBefore, int ceAfter, double instabilityBefore, double instabilityAfter,
                               double distanceBefore, double distanceAfter) {
    }

    public record EdgeDelta(String source, String target, long referencesBefore, long referencesAfter, String status) {
    }

    /** Architecture drift between two analysed commits. */
    public record Comparison(TrendPoint base, TrendPoint head, List<String> addedPackages, List<String> removedPackages,
                             List<EdgeDelta> addedEdges, List<EdgeDelta> removedEdges, List<EdgeDelta> changedEdges,
                             List<List<String>> newCycles, List<List<String>> resolvedCycles,
                             List<PackageDelta> packageDeltas, List<String> newViolations, List<String> resolvedViolations,
                             List<String> findings) {
    }
}
