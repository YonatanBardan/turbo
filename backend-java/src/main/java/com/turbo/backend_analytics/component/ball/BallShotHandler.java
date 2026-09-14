package com.turbo.backend_analytics.component.ball;

import com.turbo.backend_analytics.component.ShotPhysicsEngine;
import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Shot;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.turbo.backend_analytics.component.ball.BallSession.BALL;
import static com.turbo.backend_analytics.util.GeometryUtil.findHighestConfidence;

class BallShotHandler {

    private final ShotPhysicsEngine physicsEngine;

    BallShotHandler(ShotPhysicsEngine physicsEngine) {
        this.physicsEngine = physicsEngine;
    }

    void handleShot(
            Map<Integer, List<DetectionBox>> frames,
            int frame,
            int maxFrame,
            BallSession session
    ) {
        ShotPhysicsEngine.ShotFlight flight = physicsEngine.analyzeFlight(
                session.hoop,
                frames,
                frame,
                maxFrame,
                session.ballPath
        );
        if (flight != null && flight.ballPath() != null) {
            session.ballPath.clear();
            session.ballPath.addAll(flight.ballPath());
        }
        int endFrame = flight != null ? flight.endFrame() : frame;
        finishShot(session, endFrame);

        List<DetectionBox> endBoxes = frames.getOrDefault(endFrame, List.of());
        DetectionBox ball = findHighestConfidence(endBoxes, BALL);
        session.enterLoose(ball, endFrame);
        session.resumeAt = endFrame;
    }

    void finishShot(BallSession session, int finalFrame) {
        DetectionBox shooterBox = session.lastHolderBox;
        int shooterId = session.lastHolderId;
        if (shooterBox == null || session.hoop == null || shooterId < 0) {
            return;
        }

        Optional<Shot> generated = physicsEngine.generateShot(
                session.hoop,
                shooterBox,
                session.ballPath,
                session.shotTime,
                finalFrame
        );
        if (generated != null && generated.isPresent()) {
            boolean recentPass = session.lastPasserId >= 0
                    && session.lastPasserId != shooterId
                    && (finalFrame - session.lastPassFrame) <= session.assistWindowFrames;
            Shot raw = generated.get();
            boolean assist = raw.isMake() && recentPass;
            Shot credited = raw.withCredit(shooterId, recentPass ? session.lastPasserId : -1, assist);
            session.shots.add(credited);
            session.creditStats(credited);
        }
    }
}
