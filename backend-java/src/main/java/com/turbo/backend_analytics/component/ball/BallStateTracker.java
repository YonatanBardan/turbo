package com.turbo.backend_analytics.component.ball;

import com.turbo.backend_analytics.component.ShotPhysicsEngine;
import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.GameAnalysis;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
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

        if (detections == null || detections.isEmpty() || session.hoop == null) {
            return new GameAnalysis(session.shots, session.freezeStats());
        }

        Map<Integer, List<DetectionBox>> frames = detections.stream()
                .collect(Collectors.groupingBy(DetectionBox::frameIndex));
        int maxFrame = frames.keySet().stream().max(Integer::compareTo).orElse(0);

        int i = 0;
        while (i <= maxFrame) {
            List<DetectionBox> boxes = frames.getOrDefault(i, List.of());
            DetectionBox hoop = findHighestConfidence(boxes, HOOP);
            if (hoop != null) {
                session.hoop = hoop;
            }

            DetectionBox ball = findHighestConfidence(boxes, BALL);
            List<DetectionBox> players = findAllClass(boxes, PLAYER);
            session.frame = i;

            switch (session.phase) {
                case POSSESSED -> BallPossessionHandler.handlePossessed(ball, players, session, i);
                case LOOSE -> BallPossessionHandler.handleLoose(ball, players, session, i);
                case SHOT -> { }
            }

            if (session.phase == BallSession.Phase.SHOT) {
                shotHandler.handleShot(frames, i, maxFrame, session);
                i = session.resumeAt;
                session.resumeAt = -1;
                continue;
            }

            i++;
        }

        return new GameAnalysis(session.shots, session.freezeStats());
    }
}
