package com.turbo.backend_analytics.component;

import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Player;
import com.turbo.backend_analytics.dto.PlayerStats;
import com.turbo.backend_analytics.dto.RosterCleanupResult;
import com.turbo.backend_analytics.dto.ScoredAppearanceVector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerRosterCleanerTest {

    private final PlayerRosterCleaner cleaner = new PlayerRosterCleaner();

    @Test
    void mergesSimilarNonOverlappingIdsToSmallerIdAndRewritesPlayerBoxes() {
        Player three = player(3, new float[]{1f, 0f, 0f});
        Player eleven = player(11, new float[]{0.99f, 0.01f, 0f});

        DetectionBox p3 = playerBox(0, 3);
        DetectionBox ball = new DetectionBox(0, 0, 0.9, 1, 1, 2, 2, 1);
        DetectionBox p11 = playerBox(20, 11);

        RosterCleanupResult result = cleaner.clean(List.of(three, eleven), List.of(p3, ball, p11));

        assertEquals(1, result.players().size());
        assertEquals(3, result.players().getFirst().id());
        assertEquals(3, result.detections().getFirst().trackId());
        assertEquals(1, result.detections().get(1).trackId());
        assertEquals(3, result.detections().get(2).trackId());
    }

    @Test
    void doesNotMergePlayersWhoShareAFrame() {
        Player three = player(3, new float[]{1f, 0f, 0f});
        Player eleven = player(11, new float[]{1f, 0f, 0f});

        RosterCleanupResult result = cleaner.clean(
                List.of(three, eleven),
                List.of(playerBox(0, 3), playerBox(0, 11))
        );

        assertEquals(2, result.players().size());
        assertEquals(3, result.detections().get(0).trackId());
        assertEquals(11, result.detections().get(1).trackId());
        assertTrue(result.players().stream().anyMatch(player -> player.id() == 11));
    }

    private static Player player(int id, float[] vector) {
        return new Player(id, new PlayerStats(0, List.of()), List.of(new ScoredAppearanceVector(0.0, vector)));
    }

    private static DetectionBox playerBox(int frame, int trackId) {
        return new DetectionBox(frame, 2, 0.9, 0, 0, 10, 40, trackId);
    }
}
