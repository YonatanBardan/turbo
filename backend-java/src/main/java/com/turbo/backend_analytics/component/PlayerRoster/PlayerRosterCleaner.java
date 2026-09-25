package com.turbo.backend_analytics.component;

import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Player;
import com.turbo.backend_analytics.dto.PlayerStats;
import com.turbo.backend_analytics.dto.RosterCleanupResult;
import com.turbo.backend_analytics.dto.ScoredAppearanceVector;
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
        // If null, saves empty list
        List<Player> roster = rawRoster != null ? new ArrayList<>(rawRoster) : new ArrayList<>();
        List<DetectionBox> boxes = detections != null ? detections : List.of();

        // Skip and remove players with 0 vectors
        List<Integer> noVectors = new ArrayList<>();
        for (Player player : roster)
            if (player.appearanceVectors() == null || player.appearanceVectors().isEmpty())
                noVectors.add(player.id());
        for (int id : noVectors)
            RemovePlayer(roster, id);

        Map<Integer, Integer> alias = mergeIds(roster, boxes);            // Merge similar players Id's
        List<DetectionBox> rewritten = rewritePlayerBoxes(boxes, alias);  // Update the boxes with the new merged Id's
        return new RosterCleanupResult(roster, rewritten);                 // Returns the cleanup result
    }

    private Map<Integer, Integer> mergeIds(List<Player> roster, List<DetectionBox> boxes){
        Map<Integer, Integer> merged = new HashMap<>();

        for (Player player : roster)
            merged.put(player.id(), player.id());     // Initialize the map with the player Id's

        Map<Integer, Set<Integer>> sameFrameIds = buildSameFrameIds(boxes);                      // Build a map of players that detected in the same frame
        Map<Integer, Map<Integer, Double>> similarityMatrix = buildSimilarityMatrix(roster);     // Build a matrix of similarity between players
        List<Edge> edges = buildEdges(sameFrameIds, similarityMatrix, roster);                  // Build a list of edges between players - if score above threshhold and not in the same frame

        while(!edges.isEmpty()){
            Edge curr = edges.remove(0);

            // Gets the root id's of the players
            int player1Id = curr.player1();
            int player2Id = curr.player2();
            while (merged.getOrDefault(player1Id, player1Id) != player1Id)
                player1Id = merged.getOrDefault(player1Id, player1Id);
            while (merged.getOrDefault(player2Id, player2Id) != player2Id)
                player2Id = merged.getOrDefault(player2Id, player2Id);


            Set<Integer> conflictSet1 = sameFrameIds.getOrDefault(player1Id, new HashSet<>());
            Set<Integer> conflictSet2 = sameFrameIds.getOrDefault(player2Id, new HashSet<>());
            sameFrameIds.put(player1Id, conflictSet1);                                       // Update the map with the new conflict set or saving the new one

            if (player1Id == player2Id) continue; // Skip if same id

            if (!conflictSet1.contains(player2Id)){
                merged.put(player2Id, player1Id);                                            // Update the map with the new merged Id's
                mergePlayersAppearances(roster, player1Id, player2Id);                       // Merge the players vectors/appearances
                UpdateConflicts(sameFrameIds, roster, conflictSet1, conflictSet2, player1Id, player2Id); // Update the conflicts sets and remove the merged player
                RemovePlayer(roster, player2Id);                                                          // Delete the merged player from the roster
            }
        }
        return merged;
    }

    // ----------------
    // --- Builders ---
    // ----------------
    private Map<Integer, Set<Integer>> buildSameFrameIds(List<DetectionBox> boxes){
        Map<Integer, Set<Integer>> sameFrameIds = new HashMap<>();

        if (boxes == null || boxes.isEmpty()) return sameFrameIds;

        int prevFrame = boxes.get(0).frameIndex();
        List<Integer> currentFrameIds = new ArrayList<>();

        for (DetectionBox box: boxes){
            if (box.classId() != PLAYER_CLASS_ID) continue;     // Skip if not a player
            if (box.frameIndex() != prevFrame){                      // New frame index
                currentFrameIds.clear();
                prevFrame = box.frameIndex();
            }

            sameFrameIds.putIfAbsent(box.trackId(), new HashSet<>()); // Initialize a set if not exists
                for (int id : currentFrameIds){
                    sameFrameIds.get(id).add(box.trackId());          // Add the box id to already exist in curr frames players
                    sameFrameIds.get(box.trackId()).add(id);          // Add the already exist in curr frame to the box id set
                }
            currentFrameIds.add(box.trackId());
        }

        return sameFrameIds;
    }

    private Map<Integer, Map<Integer, Double>> buildSimilarityMatrix(List<Player> roster){
        Map<Integer, Map<Integer, Double>> similarityMatrix = new HashMap<>();

        for (int i = 0; i < roster.size(); i++){
            Player playeri = roster.get(i);
            int idi = playeri.id();
            similarityMatrix.put(idi, new HashMap<>());   // Initialize a map for the current player i in the list

            for (int j = i + 1; j < roster.size(); j++){
                Player playerj = roster.get(j);
                int idj = playerj.id();
                similarityMatrix.get(idi)   // Add the cosine similarity to the map in cell (i, j)
                                .put(idj, AppearanceSimilarity.maxCosine(playeri.appearanceVectors(), playerj.appearanceVectors())); // Cosine similarity between the two players 
            }
        }
        return similarityMatrix;
    }

    public record Edge (int player1, int player2, double score){}
    
    private List<Edge> buildEdges(Map<Integer, Set<Integer>> sameFrameIds, Map<Integer, Map<Integer, Double>> similarityMatrix, List<Player> roster){
        List<Edge> edges = new ArrayList<>();
        Map<Integer, Integer> vectorCounts = new HashMap<>();                                         // Map of player id's and their number of vectors
        for (Player player : roster) {
            int count = player.appearanceVectors() == null ? 0 : player.appearanceVectors().size();   // Condition ? result_if_true : result_if_false;
            vectorCounts.put(player.id(), count);
        }

        // Outer loop - iterate throgh each player map (row)
        // Right - prepares the matrix as a list of rows
        // Left - receives the rows of the matrix
        for (Map.Entry<Integer, Map<Integer, Double>> row : similarityMatrix.entrySet()) {
            int player1Id = row.getKey();
            Set<Integer> currSameSet = sameFrameIds.getOrDefault(player1Id, new HashSet<>());

            // Inner loop - iterate through each outer id's maps
            //  Adds a new edge if pass threshold and not in the same frame
            for (Map.Entry<Integer, Double> cell : row.getValue().entrySet()){
                double score = cell.getValue();
                int player2Id = cell.getKey();
                int vectors1 = vectorCounts.getOrDefault(player1Id, 0);
                int vectors2 = vectorCounts.getOrDefault(player2Id, 0);
                double threshold = (vectors1 <= 2 || vectors2 <= 2) ? MERGE_THRESHOLD - 0.07 : MERGE_THRESHOLD; // If a playerhas <= 2 vectors, decrease the threshold by 0.2
                if (score > threshold && !currSameSet.contains(player2Id))
                    edges.add(new Edge(player1Id, player2Id, score));
            }
        }
        // Sorting the edges - decending order
        edges.sort(Comparator.comparingDouble(Edge::score).reversed());

        return edges;
    }

    // ----------------
    // --- Helpers ---
    // ----------------
    private void UpdateConflicts(Map<Integer, Set<Integer>> sameFrameIds, List<Player> roster, Set<Integer> conflictSet1, Set<Integer> conflictSet2, int player1Id, int player2Id){
        for (int otherId : new ArrayList<>(conflictSet2)) {
            if (otherId == player2Id) continue;
            Set<Integer> otherSet = sameFrameIds.computeIfAbsent(otherId, id -> new HashSet<>());
            otherSet.remove(player2Id);
            otherSet.add(player1Id);
        }
        conflictSet1.addAll(conflictSet2);
        conflictSet1.remove(player1Id);
    }

    private void RemovePlayer(List<Player> roster, int player2Id){  // Delete the merged player from the roster

        for (int i = 0; i < roster.size(); i++) {                                  
            if (roster.get(i).id() == player2Id) {  
                roster.remove(i);
                break;
            }
        }
    } 

    private void mergePlayersAppearances(List<Player> roster, int player1Id, int player2Id){
        Player player1 = null;
        Player player2 = null;
        int player1Index = -1;
        int count = -1;

        for (Player player : roster) {
            count++;
            if (player.id() == player1Id){ player1 = player; player1Index = count;}
            if (player.id() == player2Id) player2 = player;
        }
        List<ScoredAppearanceVector> player1Vectors = new ArrayList<>(player1.appearanceVectors());
        List<ScoredAppearanceVector> player2Vectors = player2.appearanceVectors();

        player1Vectors.addAll(player2Vectors);
        player1Vectors.sort(Comparator.comparingDouble(ScoredAppearanceVector::score).reversed());

        // Keep only the top MAX_GALLERY_VECTORS vectors and update the player's vectors
        if (player1Vectors.size() > MAX_GALLERY_VECTORS)
            player1Vectors.subList(MAX_GALLERY_VECTORS, player1Vectors.size()).clear();

        roster.set(player1Index, new Player (player1.id(), player1.stats(), player1Vectors));                      // Update the player in the list
                
              
    }

    private List<DetectionBox> rewritePlayerBoxes(List<DetectionBox> boxes, Map<Integer, Integer> alias){
        List<DetectionBox> rewritten = new ArrayList<>();
        Map<Integer, Integer> flatten = new HashMap<>();

        for (Map.Entry<Integer, Integer> entry : alias.entrySet()){     // flatten the map with finding the root id
            int currId = entry.getKey();
            int rootId = entry.getValue();

            while (alias.getOrDefault(rootId, rootId) != rootId)  
                rootId = alias.getOrDefault(rootId, rootId);

            flatten.put(currId, rootId);
        }


        for (DetectionBox box : boxes){                                 // rewrite the box ids
            if (box.classId() == PLAYER_CLASS_ID){
                int boxId = box.trackId();
                int newId = flatten.getOrDefault(boxId, boxId);

                // Add the box to the rewritten list if the new id is different from the original id
                if (newId != boxId)
                    box = new DetectionBox(box.frameIndex(), box.classId(), box.confidence(), box.x(), box.y(), box.width(), box.height(), newId);
            }

            rewritten.add(box);
        }
        return rewritten;
    }
}