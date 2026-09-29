package com.turbo.backend_analytics.component.PlayerRoster;

import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Player.Player;
import com.turbo.backend_analytics.dto.Player.PlayerAppearance;
import com.turbo.backend_analytics.dto.Player.PlayerStats;
import com.turbo.backend_analytics.dto.Player.ScoredAppearanceVector;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Component
public class PlayerRosterBuilder {

    private static final int PLAYER_CLASS_ID = 2;

    // Receives: list of detections and list of players appearances (id, vectors)
    public List<Player> build(List<DetectionBox> detections, List<PlayerAppearance> appearances) {
        Set<Integer> ids = new TreeSet<>();
        Map<Integer, List<ScoredAppearanceVector>> vectorsById = new HashMap<>();

        // Add all id from detections to the set
        if (detections != null) {
            for (DetectionBox box : detections) {
                if (box.classId() == PLAYER_CLASS_ID && box.trackId() >= 0) {
                    ids.add(box.trackId());
                }
            }
        }

        if (appearances != null) {
            for (PlayerAppearance appearance : appearances) {
                ids.add(appearance.id());
                List<ScoredAppearanceVector> vectors = appearance.appearanceVectors();

                // Add vectors to the map empty list if null
                if (vectors != null) {
                    vectorsById.put(appearance.id(), vectors);
                } else {vectorsById.put(appearance.id(), new ArrayList<>());}
            }
        }

        // Build the roster
        List<Player> roster = new ArrayList<>(ids.size());
        for (Integer id : ids) {
            List<ScoredAppearanceVector> vectors = vectorsById.getOrDefault(id, List.of());     // Get vectors from the map
            roster.add(new Player(id, new PlayerStats(0, List.of()), vectors));  // Add player to the roster
        }
        return roster;
    }
}
