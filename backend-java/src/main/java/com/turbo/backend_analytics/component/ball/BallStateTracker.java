package com.turbo.backend_analytics.component.ball;

import com.turbo.backend_analytics.component.ShotPhysicsEngine;
import com.turbo.backend_analytics.dto.BallFrameState;
import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.GameAnalysis;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static com.turbo.backend_analytics.component.ball.BallSession.ASSIST_WINDOW_SECONDS;
import static com.turbo.backend_analytics.component.ball.BallSession.BALL;
import static com.turbo.backend_analytics.component.ball.BallSession.HOOP;
import static com.turbo.backend_analytics.component.ball.BallSession.PLAYER;
import static com.turbo.backend_analytics.util.GeometryUtil.findAllClass;
import static com.turbo.backend_analytics.util.GeometryUtil.findClass;
import static com.turbo.backend_analytics.util.GeometryUtil.findHighestConfidence;

@Component
public class BallStateTracker {

    private final BallShotHandler shotHandler;

    public BallStateTracker(ShotPhysicsEngine physicsEngine) {
        this.shotHandler = new BallShotHandler(physicsEngine);
    }

    public GameAnalysis analyze(List<DetectionBox> detections, double fps) {
        BallSession session = new BallSession();
        session.fps = fps > 0 ? fps : 30.0;
        session.hoop = findClass(detections, HOOP);
        session.assistWindowFrames = Math.max(1, (int) Math.round(session.fps * ASSIST_WINDOW_SECONDS));

        Map<Integer, String> labels = new TreeMap<>();
        if (detections == null || detections.isEmpty() || session.hoop == null) {
            return new GameAnalysis(session.shots, session.freezeStats(), List.of());
        }

        Map<Integer, List<DetectionBox>> frames = detections.stream()
                .collect(Collectors.groupingBy(DetectionBox::frameIndex));
        int maxFrame = frames.keySet().stream().max(Integer::compareTo).orElse(0);

        int i = 0;
        while (i <= maxFrame) {
            List<DetectionBox> boxes = frames.getOrDefault(i, List.of());  // Get the boxes for the current frame
            DetectionBox hoop = findHighestConfidence(boxes, HOOP);        // Find the hoop with the highest confidence
            if (hoop != null) {
                session.hoop = hoop;
            }

            DetectionBox ball = findHighestConfidence(boxes, BALL);        // Find the ball with the highest confidence
            List<DetectionBox> players = findAllClass(boxes, PLAYER);      // Find all the players in the current frame
            session.frame = i;

            switch (session.phase) {
                case POSSESSED -> BallPossessionHandler.handlePossessed(ball, players, session, i);
                case LOOSE -> BallPossessionHandler.handleLoose(ball, players, session, i);
                case SHOT -> { }
            }

            if (session.phase == BallSession.Phase.POSSESSED) {
                session.notePossession(i, session.holder);
            }

            if (session.phase == BallSession.Phase.SHOT) {
                int countStart = session.shotCountStartFrame();
                int shooterId = session.resolveShotShooter(countStart);
                String spread = session.possessionSpread(countStart);
                String shotLabel = shooterId >= 0 ? "shot by id " + shooterId : "shot";
                shotHandler.handleShot(frames, i, maxFrame, session);
                int end = session.resumeAt;
                int from = Math.max(0, countStart - BallSession.SHOT_LOOKBACK_FRAMES);
                for (int f = from; f < i; f++) {
                    String existing = labels.get(f);
                    if (existing != null && !existing.startsWith("shot") && !existing.contains("\n") && !spread.isEmpty()) {
                        labels.put(f, existing + "\n" + spread);
                    }
                }
                for (int f = i; f < end; f++) {
                    labels.put(f, spread.isEmpty() ? shotLabel : shotLabel + "\n" + spread);
                }
                i = end;
                session.resumeAt = -1;
                continue;
            }

            labels.put(i, session.overlayLabel());
            i++;
        }

        List<BallFrameState> ballStates = new ArrayList<>(labels.size());
        for (Map.Entry<Integer, String> entry : labels.entrySet()) {
            ballStates.add(new BallFrameState(entry.getKey(), entry.getValue()));
        }
        return new GameAnalysis(session.shots, session.freezeStats(), ballStates);
    }
}
