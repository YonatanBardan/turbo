package com.turbo.backend_analytics.component;

import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.GameAnalysis;
import com.turbo.backend_analytics.dto.PlayerStats;
import com.turbo.backend_analytics.dto.Shot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.turbo.backend_analytics.util.GeometryUtil.centerDistance;
import static com.turbo.backend_analytics.util.GeometryUtil.findAllClass;
import static com.turbo.backend_analytics.util.GeometryUtil.findBallPossessor;
import static com.turbo.backend_analytics.util.GeometryUtil.findClass;
import static com.turbo.backend_analytics.util.GeometryUtil.findHighestConfidence;

@Component
public class BallStateTracker {

    private static final int BALL = 0;
    private static final int HOOP = 1;
    private static final int PLAYER = 2;

    private static final int POSSESSION_COMMIT_FRAMES = 3;
    private static final int POSSESSION_LOST_FRAMES = 4;
    private static final int DRIBBLE_RECATCH_FRAMES = 10;
    private static final int SHOT_VERIFY_FRAMES = 8;
    private static final double ASSIST_WINDOW_SECONDS = 3.0;

    private enum Phase { POSSESSED, IN_AIR, TRACKING_SHOT }

    private final ShotPhysicsEngine physicsEngine;

    public BallStateTracker(ShotPhysicsEngine physicsEngine) {
        this.physicsEngine = physicsEngine;
    }

    public GameAnalysis analyze(List<DetectionBox> detections, double fps) {
        Session session = new Session();
        session.fps = fps > 0 ? fps : 30.0;
        session.hoop = findClass(detections, HOOP);
        session.assistWindowFrames = Math.max(1, (int) Math.round(session.fps * ASSIST_WINDOW_SECONDS));
        session.airTimeoutFrames = Math.max(SHOT_VERIFY_FRAMES, (int) Math.round(session.fps * 1.5));

        if (detections == null || detections.isEmpty() || session.hoop == null) {
            return new GameAnalysis(session.shots, freezeStats(session.stats));
        }

        Map<Integer, List<DetectionBox>> frames = detections.stream()
                .collect(Collectors.groupingBy(DetectionBox::frameIndex));
        int maxFrame = frames.keySet().stream().max(Integer::compareTo).orElse(0);

        for (int i = 0; i <= maxFrame; i++) {
            List<DetectionBox> boxes = frames.getOrDefault(i, List.of());
            DetectionBox hoop = findHighestConfidence(boxes, HOOP);
            if (hoop != null) {
                session.hoop = hoop;
            }

            DetectionBox ball = findHighestConfidence(boxes, BALL);
            List<DetectionBox> players = findAllClass(boxes, PLAYER);
            DetectionBox possessor = ball != null ? findBallPossessor(ball, players) : null;
            updatePossession(session, possessor, i);

            switch (session.phase) {
                case POSSESSED -> handlePossessed(ball, session, i);
                case IN_AIR -> handleInAir(ball, session, i);
                case TRACKING_SHOT -> handleTrackingShot(ball, session, i);
            }
        }

        return new GameAnalysis(session.shots, freezeStats(session.stats));
    }

    private void updatePossession(Session session, DetectionBox candidate, int frame) {
        if (candidate != null && candidate.trackId() >= 0) {
            session.lostFrames = 0;
            if (session.pendingPossessor != null && session.pendingPossessor.trackId() == candidate.trackId()) {
                session.pendingPossessor = candidate;
                session.pendingCount++;
            } else if (session.committedPossessor != null && session.committedPossessor.trackId() == candidate.trackId()) {
                session.committedPossessor = candidate;
                session.pendingPossessor = candidate;
                session.pendingCount = POSSESSION_COMMIT_FRAMES;
            } else {
                session.pendingPossessor = candidate;
                session.pendingCount = 1;
            }
            if (session.pendingCount >= POSSESSION_COMMIT_FRAMES) {
                session.committedPossessor = session.pendingPossessor;
            }
        } else {
            session.lostFrames++;
            if (session.lostFrames > POSSESSION_LOST_FRAMES) {
                session.pendingPossessor = null;
                session.pendingCount = 0;
            }
        }
        session.frame = frame;
    }

    private void handlePossessed(DetectionBox ball, Session session, int frame) {
        if (session.committedPossessor != null) {
            session.lastPossessorId = session.committedPossessor.trackId();
            session.lastPossessorBox = session.committedPossessor;
            session.holding = true;
        }
        if (ball == null) {
            return;
        }
        boolean stillHeld = session.committedPossessor != null
                && session.lostFrames <= POSSESSION_LOST_FRAMES;
        if (stillHeld || !session.holding) {
            return;
        }
        session.holding = false;
        startInAir(session, frame);
        session.ballPath.add(ball);
    }

    private void handleInAir(DetectionBox ball, Session session, int frame) {
        if (ball != null) {
            session.ballPath.add(ball);
        }

        DetectionBox catcher = committedCatcher(session);
        if (catcher != null) {
            resolveCatch(session, catcher, frame);
            return;
        }

        if (session.ballPath.size() >= SHOT_VERIFY_FRAMES && looksLikeShot(session)) {
            session.phase = Phase.TRACKING_SHOT;
            session.shotTime = session.ballPath.getFirst().frameIndex() / session.fps;
            return;
        }

        if (frame - session.airStartFrame >= session.airTimeoutFrames) {
            resetToWaitForPossession(session);
        }
    }

    private void handleTrackingShot(DetectionBox ball, Session session, int frame) {
        if (ball != null) {
            session.ballPath.add(ball);
        }

        DetectionBox catcher = committedCatcher(session);
        if (catcher != null) {
            resolveCatch(session, catcher, frame);
            return;
        }

        if (session.ballPath.isEmpty() || session.hoop == null) {
            return;
        }
        DetectionBox lastBall = session.ballPath.getLast();
        boolean belowHoop = lastBall.y() > session.hoop.y() + session.hoop.height();
        if (belowHoop) {
            finishShot(session, lastBall.frameIndex());
        } else if (frame - session.airStartFrame >= session.airTimeoutFrames * 2) {
            resetToWaitForPossession(session);
        }
    }

    private DetectionBox committedCatcher(Session session) {
        if (session.committedPossessor == null || session.lostFrames > POSSESSION_LOST_FRAMES) {
            return null;
        }
        if (session.pendingCount < POSSESSION_COMMIT_FRAMES) {
            return null;
        }
        return session.committedPossessor;
    }

    private void resolveCatch(Session session, DetectionBox catcher, int frame) {
        int catcherId = catcher.trackId();
        boolean sameHandler = catcherId == session.releaserId
                && (frame - session.airStartFrame) <= DRIBBLE_RECATCH_FRAMES;

        if (sameHandler) {
            session.phase = Phase.POSSESSED;
            session.holding = true;
            session.lastPossessorId = catcherId;
            session.lastPossessorBox = catcher;
            session.ballPath.clear();
            return;
        }

        if (catcherId != session.releaserId && session.releaserId >= 0) {
            session.lastPasserId = session.releaserId;
            session.lastPassFrame = frame;
        }
        session.phase = Phase.POSSESSED;
        session.holding = true;
        session.lastPossessorId = catcherId;
        session.lastPossessorBox = catcher;
        session.committedPossessor = catcher;
        session.ballPath.clear();
    }

    private void startInAir(Session session, int frame) {
        session.phase = Phase.IN_AIR;
        session.airStartFrame = frame;
        session.releaserId = session.lastPossessorId;
        session.releaserBox = session.lastPossessorBox;
        session.committedPossessor = null;
        session.pendingCount = 0;
        session.ballPath.clear();
    }

    private boolean looksLikeShot(Session session) {
        if (session.ballPath.size() < SHOT_VERIFY_FRAMES || session.hoop == null) {
            return false;
        }
        DetectionBox first = session.ballPath.getFirst();
        DetectionBox last = session.ballPath.getLast();
        boolean goingUp = last.y() < first.y() - 4.0;
        double firstDist = centerDistance(first, session.hoop);
        double lastDist = centerDistance(last, session.hoop);
        boolean towardHoop = lastDist < firstDist
                || last.y() < session.hoop.y() + session.hoop.height();
        boolean notDownwardDump = last.y() <= first.y() + 8.0;
        return goingUp && towardHoop && notDownwardDump;
    }

    private void finishShot(Session session, int finalFrame) {
        DetectionBox shooterBox = session.releaserBox != null ? session.releaserBox : session.lastPossessorBox;
        int shooterId = session.releaserId >= 0 ? session.releaserId : session.lastPossessorId;
        if (shooterBox == null || session.hoop == null || shooterId < 0) {
            resetToWaitForPossession(session);
            return;
        }

        Optional<Shot> generated = physicsEngine.generateShot(
                session.hoop,
                shooterBox,
                session.ballPath,
                session.shotTime,
                finalFrame
        );
        if (generated.isPresent()) {
            boolean recentPass = session.lastPasserId >= 0
                    && session.lastPasserId != shooterId
                    && (finalFrame - session.lastPassFrame) <= session.assistWindowFrames;
            Shot raw = generated.get();
            boolean assist = raw.isMake() && recentPass;
            Shot credited = raw.withCredit(shooterId, recentPass ? session.lastPasserId : -1, assist);
            session.shots.add(credited);
            creditStats(session, credited);
        }
        resetToWaitForPossession(session);
    }

    private void creditStats(Session session, Shot shot) {
        MutableStats shooter = session.stats.computeIfAbsent(shot.shooterTrackId(), id -> new MutableStats());
        shooter.shots.add(shot);
        if (shot.assist() && shot.passerTrackId() >= 0) {
            session.stats.computeIfAbsent(shot.passerTrackId(), id -> new MutableStats()).assists++;
        }
    }

    private void resetToWaitForPossession(Session session) {
        session.phase = Phase.POSSESSED;
        session.holding = false;
        session.ballPath.clear();
        session.committedPossessor = null;
        session.pendingPossessor = null;
        session.pendingCount = 0;
        session.lostFrames = POSSESSION_LOST_FRAMES + 1;
    }

    private Map<Integer, PlayerStats> freezeStats(Map<Integer, MutableStats> mutable) {
        Map<Integer, PlayerStats> frozen = new HashMap<>();
        for (Map.Entry<Integer, MutableStats> entry : mutable.entrySet()) {
            frozen.put(entry.getKey(), new PlayerStats(entry.getValue().assists, List.copyOf(entry.getValue().shots)));
        }
        return frozen;
    }

    private static class MutableStats {
        int assists;
        final List<Shot> shots = new ArrayList<>();
    }

    private static class Session {
        Phase phase = Phase.POSSESSED;
        DetectionBox hoop;
        DetectionBox committedPossessor;
        DetectionBox pendingPossessor;
        DetectionBox lastPossessorBox;
        DetectionBox releaserBox;
        final List<DetectionBox> ballPath = new ArrayList<>();
        final List<Shot> shots = new ArrayList<>();
        final Map<Integer, MutableStats> stats = new HashMap<>();

        double fps = 30.0;
        double shotTime = 0.0;
        int frame;
        int pendingCount;
        int lostFrames = POSSESSION_LOST_FRAMES + 1;
        int lastPossessorId = -1;
        int lastPasserId = -1;
        int lastPassFrame = -1;
        int releaserId = -1;
        int airStartFrame;
        int assistWindowFrames = 90;
        int airTimeoutFrames = 45;
        boolean holding;
    }
}
