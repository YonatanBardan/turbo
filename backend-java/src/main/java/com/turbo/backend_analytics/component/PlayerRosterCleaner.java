package com.turbo.backend_analytics.component;

import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Player;
import com.turbo.backend_analytics.dto.PlayerStats;
import com.turbo.backend_analytics.dto.RosterCleanupResult;
import com.turbo.backend_analytics.util.AppearanceSimilarity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Component
public class PlayerRosterCleaner {

    static final int PLAYER_CLASS_ID = 2;
    static final double MERGE_THRESHOLD = 0.70;
    static final int MAX_GALLERY_VECTORS = 15;

    public RosterCleanupResult clean(List<Player> rawRoster, List<DetectionBox> detections) {
        List<Player> roster = rawRoster != null ? rawRoster : List.of();
        List<DetectionBox> boxes = detections != null ? detections : List.of();

        Map<Integer, Integer> alias = mergeAliases(roster, boxes);
        List<DetectionBox> rewritten = rewritePlayerBoxes(boxes, alias);
        List<Player> cleaned = mergePlayers(roster, alias);
        return new RosterCleanupResult(cleaned, rewritten, alias);
    }

    Map<Integer, Integer> mergeAliases(List<Player> roster, List<DetectionBox> boxes) {
        Map<Integer, Integer> parent = new HashMap<>();
        Map<Integer, Set<Integer>> members = new HashMap<>();
        for (Player player : roster) {
            parent.put(player.id(), player.id());
            members.put(player.id(), new HashSet<>(List.of(player.id())));
        }

        Map<Integer, Set<Integer>> coOccur = buildCoOccurrence(boxes);
        List<ScoredPair> pairs = scoredPairs(roster, coOccur);
        pairs.sort(Comparator.comparingDouble(ScoredPair::score).reversed());

        for (ScoredPair pair : pairs) {
            int rootA = find(parent, pair.idA());
            int rootB = find(parent, pair.idB());
            if (rootA == rootB) {
                continue;
            }
            if (componentsCoOccur(members.get(rootA), members.get(rootB), coOccur)) {
                continue;
            }
            union(parent, members, rootA, rootB);
        }

        Map<Integer, Integer> alias = new HashMap<>();
        for (Player player : roster) {
            alias.put(player.id(), find(parent, player.id()));
        }
        return alias;
    }

    List<DetectionBox> rewritePlayerBoxes(List<DetectionBox> boxes, Map<Integer, Integer> alias) {
        List<DetectionBox> rewritten = new ArrayList<>(boxes.size());
        for (DetectionBox box : boxes) {
            if (box.classId() != PLAYER_CLASS_ID || box.trackId() < 0) {
                rewritten.add(box);
                continue;
            }
            int canonical = alias.getOrDefault(box.trackId(), box.trackId());
            if (canonical == box.trackId()) {
                rewritten.add(box);
            } else {
                rewritten.add(new DetectionBox(
                        box.frameIndex(),
                        box.classId(),
                        box.confidence(),
                        box.x(),
                        box.y(),
                        box.width(),
                        box.height(),
                        canonical
                ));
            }
        }
        return rewritten;
    }

    private List<Player> mergePlayers(List<Player> roster, Map<Integer, Integer> alias) {
        Map<Integer, List<float[]>> vectorsByCanonical = new TreeMap<>();
        for (Player player : roster) {
            int canonical = alias.getOrDefault(player.id(), player.id());
            List<float[]> merged = vectorsByCanonical.computeIfAbsent(canonical, id -> new ArrayList<>());
            if (player.appearanceVectors() == null) {
                continue;
            }
            for (float[] vector : player.appearanceVectors()) {
                if (merged.size() >= MAX_GALLERY_VECTORS) {
                    break;
                }
                merged.add(vector);
            }
        }

        List<Player> cleaned = new ArrayList<>(vectorsByCanonical.size());
        for (Map.Entry<Integer, List<float[]>> entry : vectorsByCanonical.entrySet()) {
            cleaned.add(new Player(entry.getKey(), new PlayerStats(0, List.of()), entry.getValue()));
        }
        return cleaned;
    }

    private Map<Integer, Set<Integer>> buildCoOccurrence(List<DetectionBox> boxes) {
        Map<Integer, List<Integer>> idsByFrame = new HashMap<>();
        for (DetectionBox box : boxes) {
            if (box.classId() != PLAYER_CLASS_ID || box.trackId() < 0) {
                continue;
            }
            idsByFrame.computeIfAbsent(box.frameIndex(), frame -> new ArrayList<>()).add(box.trackId());
        }

        Map<Integer, Set<Integer>> coOccur = new HashMap<>();
        for (List<Integer> ids : idsByFrame.values()) {
            for (int i = 0; i < ids.size(); i++) {
                int a = ids.get(i);
                coOccur.computeIfAbsent(a, id -> new HashSet<>());
                for (int j = i + 1; j < ids.size(); j++) {
                    int b = ids.get(j);
                    if (a == b) {
                        continue;
                    }
                    coOccur.computeIfAbsent(a, id -> new HashSet<>()).add(b);
                    coOccur.computeIfAbsent(b, id -> new HashSet<>()).add(a);
                }
            }
        }
        return coOccur;
    }

    private List<ScoredPair> scoredPairs(List<Player> roster, Map<Integer, Set<Integer>> coOccur) {
        List<ScoredPair> pairs = new ArrayList<>();
        for (int i = 0; i < roster.size(); i++) {
            Player a = roster.get(i);
            if (!hasVectors(a)) {
                continue;
            }
            Set<Integer> aNeighbors = coOccur.getOrDefault(a.id(), Set.of());
            for (int j = i + 1; j < roster.size(); j++) {
                Player b = roster.get(j);
                if (!hasVectors(b) || aNeighbors.contains(b.id())) {
                    continue;
                }
                double score = AppearanceSimilarity.maxCosine(a.appearanceVectors(), b.appearanceVectors());
                if (score >= MERGE_THRESHOLD) {
                    pairs.add(new ScoredPair(a.id(), b.id(), score));
                }
            }
        }
        return pairs;
    }

    private static boolean hasVectors(Player player) {
        return player.appearanceVectors() != null && !player.appearanceVectors().isEmpty();
    }

    private static boolean componentsCoOccur(
            Set<Integer> membersA,
            Set<Integer> membersB,
            Map<Integer, Set<Integer>> coOccur
    ) {
        if (membersA == null || membersB == null) {
            return false;
        }
        for (int idA : membersA) {
            Set<Integer> neighbors = coOccur.get(idA);
            if (neighbors == null) {
                continue;
            }
            for (int idB : membersB) {
                if (neighbors.contains(idB)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int find(Map<Integer, Integer> parent, int id) {
        int current = parent.getOrDefault(id, id);
        if (current != id) {
            current = find(parent, current);
            parent.put(id, current);
        }
        return current;
    }

    private static void union(
            Map<Integer, Integer> parent,
            Map<Integer, Set<Integer>> members,
            int rootA,
            int rootB
    ) {
        int root = Math.min(rootA, rootB);
        int other = Math.max(rootA, rootB);
        parent.put(other, root);
        members.get(root).addAll(members.get(other));
        members.remove(other);
    }

    private record ScoredPair(int idA, int idB, double score) {}
}
