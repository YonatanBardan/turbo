package com.turbo.backend_analytics.component;

import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Player;
import com.turbo.backend_analytics.dto.PlayerAppearance;
import com.turbo.backend_analytics.dto.PlayerStats;
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

    public List<Player> build(List<DetectionBox> detections, List<PlayerAppearance> appearances) {
        Set<Integer> ids = new TreeSet<>();
        Map<Integer, List<float[]>> vectorsById = new HashMap<>();

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
                List<float[]> vectors = appearance.appearanceVectors();
                vectorsById.put(
                        appearance.id(),
                        vectors != null ? vectors : List.of()
                );
            }
        }

        List<Player> roster = new ArrayList<>(ids.size());
        for (Integer id : ids) {
            List<float[]> vectors = vectorsById.getOrDefault(id, List.of());
            roster.add(new Player(id, new PlayerStats(0, List.of()), vectors));
        }
        return roster;
    }
}
