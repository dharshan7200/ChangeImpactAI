package com.changeimpact.defect;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.blame.BlameResult;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.*;

/**
 * SZZ (Śliwerski, Zimmermann, Zeller 2005), the variant that blames the lines a fix deletes or modifies in the fix's
 * parent revision: the commits that last touched those lines are labelled bug-introducing, linked to the fix. Blank,
 * comment and import lines are ignored, and very large fixes are skipped ({@link JitDatasetBuilder}); fixes that only
 * add lines cannot be traced (a known SZZ limitation). Bug fixes are recognised from commit messages, so labels are
 * heuristic and noisy — they are used for evaluation, never presented as facts about a commit.
 */
public final class SzzLabeler {

    /** A fix and one commit that introduced lines it changed. */
    public record Link(String introducing, String fix, long fixTime) {}

    public record Result(List<Link> links, int fixes, int tracedFixes, int untraceableFixes) {}

    public Result label(Git git, List<JitDatasetBuilder.Sample> samples) throws Exception {
        Set<String> inWindow = new HashSet<>();
        samples.forEach(s -> inWindow.add(s.sha()));
        List<Link> links = new ArrayList<>();
        int fixes = 0, traced = 0, untraceable = 0;
        for (JitDatasetBuilder.Sample s : samples) {
            if (!s.fix()) continue;
            fixes++;
            if (s.fixedLines().isEmpty() || s.parent() == null) { untraceable++; continue; }
            Set<String> introducing = new TreeSet<>();
            for (var e : s.fixedLines().entrySet()) {
                BlameResult blame = git.blame().setStartCommit(ObjectId.fromString(s.parent()))
                        .setFilePath(e.getKey()).setFollowFileRenames(true).call();
                if (blame == null) continue;
                for (int line : e.getValue()) {
                    if (line >= blame.getResultContents().size()) continue;
                    RevCommit source = blame.getSourceCommit(line);
                    if (source != null && !source.getName().equals(s.sha()) && inWindow.contains(source.getName())) {
                        introducing.add(source.getName());
                    }
                }
            }
            if (introducing.isEmpty()) { untraceable++; continue; }
            traced++;
            for (String sha : introducing) links.add(new Link(sha, s.sha(), s.time()));
        }
        return new Result(links, fixes, traced, untraceable);
    }
}
