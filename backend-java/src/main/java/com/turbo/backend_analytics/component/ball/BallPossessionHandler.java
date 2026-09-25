package com.turbo.backend_analytics.component.ball;

import com.turbo.backend_analytics.dto.DetectionBox;

import java.util.List;

import static com.turbo.backend_analytics.component.ball.BallSession.LOOSE_SAME_HOLDER_DELAY;
import static com.turbo.backend_analytics.component.ball.BallSession.MISSING_BALL_PASS_FRAMES;
import static com.turbo.backend_analytics.component.ball.BallSession.SHOT_RISE_MIN_POINTS;
import static com.turbo.backend_analytics.component.ball.BallSession.SHOT_RISE_PX;
import static com.turbo.backend_analytics.component.ball.BallSession.SHOT_SHOULDER_RATIO;
import static com.turbo.backend_analytics.util.GeometryUtil.centerY;
import static com.turbo.backend_analytics.util.GeometryUtil.findBallPossessor;
import static com.turbo.backend_analytics.util.GeometryUtil.isNearPlayer;
import static com.turbo.backend_analytics.util.GeometryUtil.findPlayerByTrackId;

class BallPossessionHandler {

    static void handlePossessed(DetectionBox ball, List<DetectionBox> players, BallSession session, int frame) {
        if (session.holder == null) {
            DetectionBox possessor = ball != null ? findBallPossessor(ball, players) : null;
            if (possessor != null) {
                session.enterPossessed(possessor, false);
                session.markPossessedRelease(ball, possessor);
            }
            return;
        }

        DetectionBox liveHolder = findPlayerByTrackId(players, session.holder.trackId());
        if (liveHolder != null) {
            session.holder = liveHolder;
            session.lastHolderBox = liveHolder;
        }

        if (ball == null) {
            session.framesWithoutBall++;
            return;
        }

        int missed = session.framesWithoutBall;
        session.framesWithoutBall = 0;
        if (missed >= MISSING_BALL_PASS_FRAMES) {
            DetectionBox nextHolder = findBallPossessor(ball, players);
            if (nextHolder != null && nextHolder.trackId() != session.holder.trackId()) {
                session.enterPossessed(nextHolder, true);
                session.markPossessedRelease(ball, nextHolder);
                return;
            }
        }

        DetectionBox distanceRef = session.holder != null ? session.holder : session.lastHolderBox;
        if (distanceRef == null) {
            return;
        }

        if (!isNearPlayer(ball, distanceRef)) {
            session.enterLoose(ball, frame);
            return;
        }
        session.markPossessedRelease(ball, distanceRef);
    }

    static void handleLoose(DetectionBox ball, List<DetectionBox> players, BallSession session, int frame) {
        if (ball != null) {
            session.ballPath.add(ball);
        }

        if (ball != null && yReducedSignificantly(session, players)) {
            session.enterShot(frame);
            return;
        }

        // High release, then any loose-path ball above the hoop Y, becomes a shot.
        if (session.phase != BallSession.Phase.SHOT
                && session.releasedFromTop
                && reachedHoopY(session.hoop, session.ballPath)) {
            session.enterShot(frame);
            return;
        }

        DetectionBox catcher = ball != null ? findBallPossessor(ball, players) : null;
        if (catcher == null) {
            return;
        }
        boolean sameHolder = catcher.trackId() == session.lastHolderId;
        if (sameHolder && (frame - session.looseStartFrame) < LOOSE_SAME_HOLDER_DELAY) {
            return;
        }
        session.enterPossessed(catcher, !sameHolder);
    }

    static boolean yReducedSignificantly(BallSession session, List<DetectionBox> players) {
        if (session.ballPath.size() < SHOT_RISE_MIN_POINTS) {
            return false;
        }
        DetectionBox last = session.ballPath.getLast();
        boolean movedUp = session.looseStartY - centerY(last) >= SHOT_RISE_PX;
        if (!movedUp) {
            return false; 
        }

        DetectionBox shooter = findPlayerByTrackId(players, session.lastHolderId);
        if (shooter == null) {
            shooter = session.lastHolderBox;
        }
        if (shooter == null) {
            return true;
        }

        // Dribble bounce comes up in the legs/torso. A shot is released around the shoulders.
        double shoulderLine = shooter.y() + (shooter.height() * SHOT_SHOULDER_RATIO);
        if (centerY(last) > shoulderLine) {
            return false;
        }
        return true;
    }

    static boolean reachedHoopY(DetectionBox hoop, List<DetectionBox> ballPath) {
        if (hoop == null || ballPath == null || ballPath.isEmpty()) {
            return false;
        }
        double hoopY = hoop.y();
        for (DetectionBox ball : ballPath) {
            if (centerY(ball) <= hoopY) {
                return true;
            }
        }
        return false;
    }
}
