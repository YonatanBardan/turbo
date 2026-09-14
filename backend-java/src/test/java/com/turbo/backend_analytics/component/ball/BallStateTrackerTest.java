package com.turbo.backend_analytics.component.ball;

import com.turbo.backend_analytics.component.ShotPhysicsEngine;
import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.GameAnalysis;
import com.turbo.backend_analytics.dto.PlayerStats;
import com.turbo.backend_analytics.dto.Shot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BallStateTrackerTest {

    @Mock
    ShotPhysicsEngine physicsEngine;

    @Test
    void creditsMadeShotToShooterAndAssistToPasser() {
        stubMadeShot();

        BallStateTracker tracker = new BallStateTracker(physicsEngine);
        GameAnalysis result = tracker.analyze(passThenShotScript(), 30.0);

        assertEquals(1, result.shots().size());
        Shot shot = result.shots().getFirst();
        assertEquals(7, shot.shooterTrackId());
        assertEquals(3, shot.passerTrackId());
        assertTrue(shot.assist());
        assertTrue(shot.isMake());

        PlayerStats passer = result.statsByPlayerId().get(3);
        PlayerStats shooter = result.statsByPlayerId().get(7);
        assertEquals(1, passer.assists());
        assertEquals(1, shooter.shots().size());
        assertEquals(0, shooter.assists());
    }

    @Test
    void creditsShotAfterBallHiddenBehindDribbler() {
        stubMadeShot();

        BallStateTracker tracker = new BallStateTracker(physicsEngine);
        GameAnalysis result = tracker.analyze(hiddenThenShotScript(), 30.0);

        assertEquals(1, result.shots().size());
        Shot shot = result.shots().getFirst();
        assertEquals(7, shot.shooterTrackId());
        assertTrue(shot.isMake());
        assertEquals(1, result.statsByPlayerId().get(7).shots().size());
    }

    @Test
    void dribbleDoesNotBecomeAShot() {
        BallStateTracker tracker = new BallStateTracker(physicsEngine);
        GameAnalysis result = tracker.analyze(dribbleScript(), 30.0);

        assertEquals(0, result.shots().size());
        verify(physicsEngine, never()).generateShot(any(), any(), anyList(), anyDouble(), anyInt());
    }

    @Test
    void bounceBelowShouldersIsNotAShot() {
        BallStateTracker tracker = new BallStateTracker(physicsEngine);
        GameAnalysis result = tracker.analyze(lowBounceScript(), 30.0);

        assertEquals(0, result.shots().size());
        verify(physicsEngine, never()).generateShot(any(), any(), anyList(), anyDouble(), anyInt());
    }

    private void stubMadeShot() {
        when(physicsEngine.analyzeFlight(any(), any(), anyInt(), anyInt(), anyList()))
                .thenAnswer(invocation -> {
                    int max = invocation.getArgument(3);
                    List<DetectionBox> prior = invocation.getArgument(4);
                    return new ShotPhysicsEngine.ShotFlight(max, prior);
                });
        when(physicsEngine.generateShot(any(), any(), anyList(), anyDouble(), anyInt()))
                .thenAnswer(invocation -> {
                    DetectionBox shooter = invocation.getArgument(1);
                    int frame = invocation.getArgument(4);
                    return Optional.of(new Shot(shooter, 0.9, "0:01", frame, true));
                });
    }

    private List<DetectionBox> passThenShotScript() {
        List<DetectionBox> boxes = new ArrayList<>();
        DetectionBox hoop = box(0, 1, 200, 30, 40, 20, -1);
        DetectionBox passer = player(0, 3, 60);
        DetectionBox shooter = player(0, 7, 320);

        for (int f = 0; f <= 70; f++) {
            boxes.add(copy(hoop, f));
            boxes.add(copy(passer, f));
            boxes.add(copy(shooter, f));
            if (f <= 6) {
                boxes.add(ball(f, 70, 140));
            } else if (f <= 12) {
                boxes.add(ball(f, 70 + (f - 6) * 25, 140));
            } else if (f <= 22) {
                boxes.add(ball(f, 330, 140));
            } else if (f <= 40) {
                boxes.add(ball(f, 330 - (f - 22) * 8, 140 - (f - 22) * 6));
            } else {
                boxes.add(ball(f, 210, 80));
            }
        }
        return boxes;
    }

    private List<DetectionBox> hiddenThenShotScript() {
        List<DetectionBox> boxes = new ArrayList<>();
        DetectionBox hoop = box(0, 1, 200, 30, 40, 20, -1);
        DetectionBox shooter = player(0, 7, 320);

        for (int f = 0; f <= 70; f++) {
            boxes.add(copy(hoop, f));
            boxes.add(copy(shooter, f));
            if (f <= 8) {
                boxes.add(ball(f, 330, 140));
            } else if (f <= 40) {
                // Ball occluded (back to camera) — stay possessed.
            } else if (f <= 56) {
                boxes.add(ball(f, 250, 120 - (f - 40) * 6));
            } else {
                boxes.add(ball(f, 210, 80));
            }
        }
        return boxes;
    }

    private List<DetectionBox> dribbleScript() {
        List<DetectionBox> boxes = new ArrayList<>();
        DetectionBox hoop = box(0, 1, 200, 30, 40, 20, -1);
        DetectionBox dribbler = player(0, 7, 320);

        for (int f = 0; f <= 40; f++) {
            boxes.add(copy(hoop, f));
            boxes.add(copy(dribbler, f));
            if (f <= 6) {
                boxes.add(ball(f, 330, 140));
            } else if (f <= 14) {
                boxes.add(ball(f, 330, 140 + (f - 6) * 12));
            } else {
                boxes.add(ball(f, 330, 140));
            }
        }
        return boxes;
    }

    private List<DetectionBox> lowBounceScript() {
        List<DetectionBox> boxes = new ArrayList<>();
        DetectionBox hoop = box(0, 1, 200, 30, 40, 20, -1);
        DetectionBox dribbler = player(0, 7, 320);

        for (int f = 0; f <= 40; f++) {
            boxes.add(copy(hoop, f));
            boxes.add(copy(dribbler, f));
            if (f <= 6) {
                boxes.add(ball(f, 330, 140));
            } else if (f <= 12) {
                boxes.add(ball(f, 300 - (f - 6) * 8, 190 + (f - 6) * 8));
            } else if (f <= 22) {
                boxes.add(ball(f, 250, 230 - (f - 12) * 6));
            } else {
                boxes.add(ball(f, 330, 140));
            }
        }
        return boxes;
    }

    private static DetectionBox copy(DetectionBox src, int frame) {
        return new DetectionBox(frame, src.classId(), src.confidence(), src.x(), src.y(), src.width(), src.height(), src.trackId());
    }

    private static DetectionBox player(int frame, int id, double x) {
        return box(frame, 2, x, 100, 50, 140, id);
    }

    private static DetectionBox ball(int frame, double x, double y) {
        return box(frame, 0, x, y, 16, 16, 1);
    }

    private static DetectionBox box(int frame, int classId, double x, double y, double w, double h, int trackId) {
        return new DetectionBox(frame, classId, 0.9, x, y, w, h, trackId);
    }
}
