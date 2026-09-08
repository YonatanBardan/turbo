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
import static com.turbo.backend_analytics.util.GeometryUtil.centerY;
import static com.turbo.backend_analytics.util.GeometryUtil.findAllClass;
import static com.turbo.backend_analytics.util.GeometryUtil.findBallPossessor;
import static com.turbo.backend_analytics.util.GeometryUtil.findClass;
import static com.turbo.backend_analytics.util.GeometryUtil.findHighestConfidence;
import static com.turbo.backend_analytics.util.GeometryUtil.findPlayerByTrackId;

@Component
public class BallStateTracker {

    private static final int BALL = 0;
    private static final int HOOP = 1;
    private static final int PLAYER = 2;

    private static final int RELEASE_GROW_FRAMES = 2;
    private static final double DISTANCE_GROW_PX = 3.0;
    private static final int SHOT_RISE_MIN_POINTS = 3;
    private static final double SHOT_RISE_PX = 18.0;
    private static final int LOOSE_SAME_HOLDER_DELAY = 2;
    private static final double ASSIST_WINDOW_SECONDS = 3.0;

    private enum Phase { POSSESSED, LOOSE, SHOT }

    private final ShotPhysicsEngine physicsEngine;

    public BallStateTracker(ShotPhysicsEngine physicsEngine) {
        this.physicsEngine = physicsEngine;
    }

    public GameAnalysis analyze(List<DetectionBox> detections, double fps) {
        Session session = new Session();
        session.fps = fps > 0 ? fps : 30.0;
        session.hoop = findClass(detections, HOOP);
        session.assistWindowFrames = Math.max(1, (int) Math.round(session.fps * ASSIST_WINDOW_SECONDS));
        session.shotTimeoutFrames = Math.max(20, (int) Math.round(session.fps * 2.0));

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
            session.frame = i;

            switch (session.phase) {
                case POSSESSED -> handlePossessed(ball, players, session, i);
                case LOOSE -> handleLoose(ball, players, session, i);
                case SHOT -> handleShot(ball, players, session, i);
            }
        }

        return new GameAnalysis(session.shots, freezeStats(session.stats));
    }

    private void handlePossessed(DetectionBox ball, List<DetectionBox> players, Session session, int frame) {
        if (session.holder == null) {
            DetectionBox possessor = ball != null ? findBallPossessor(ball, players) : null;
            if (possessor != null) {
                enterPossessed(session, possessor, false);
            }
            return;
        }

        DetectionBox liveHolder = findPlayerByTrackId(players, session.holder.trackId());
        if (liveHolder != null) {
            session.holder = liveHolder;
            session.lastHolderBox = liveHolder;
        }

        if (ball == null) {
            return;
        }

        DetectionBox distanceRef = session.holder != null ? session.holder : session.lastHolderBox;
        if (distanceRef == null) {
            return;
        }

        double dist = centerDistance(ball, distanceRef);
        if (session.lastDistance >= 0 && dist > session.lastDistance + DISTANCE_GROW_PX) {
            session.growingFrames++;
        } else {
            session.growingFrames = 0;
        }
        session.lastDistance = dist;

        if (session.growingFrames >= RELEASE_GROW_FRAMES) {
            enterLoose(session, ball, frame);
        }
    }

    private void handleLoose(DetectionBox ball, List<DetectionBox> players, Session session, int frame) {
        if (ball != null) {
            session.ballPath.add(ball);
        }

        if (ball != null && yReducedSignificantly(session)) {
            enterShot(session, frame);
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
        enterPossessed(session, catcher, !sameHolder);
    }

    private void handleShot(DetectionBox ball, List<DetectionBox> players, Session session, int frame) {
        if (ball != null) {
            session.ballPath.add(ball);
            session.shotPeakY = Math.min(session.shotPeakY, centerY(ball));
        }

        DetectionBox catcher = ball != null ? findBallPossessor(ball, players) : null;
        if (catcher != null && catcher.trackId() == session.lastHolderId) {
            catcher = null;
        }
        boolean comingDown = ball != null && centerY(ball) > session.shotPeakY + 12.0;
        boolean timedOut = (frame - session.shotStartFrame) >= session.shotTimeoutFrames;

        if (catcher != null || comingDown || timedOut) {
            finishShot(session, frame);
            if (catcher != null) {
                enterPossessed(session, catcher, catcher.trackId() != session.lastHolderId);
            }
        }
    }

    private void enterPossessed(Session session, DetectionBox holder, boolean fromPass) {
        if (fromPass && session.lastHolderId >= 0 && holder.trackId() != session.lastHolderId) {
            session.lastPasserId = session.lastHolderId;
            session.lastPassFrame = session.frame;
        }
        session.phase = Phase.POSSESSED;
        session.holder = holder;
        session.lastHolderId = holder.trackId();
        session.lastHolderBox = holder;
        session.lastDistance = -1;
        session.growingFrames = 0;
        session.ballPath.clear();
    }

    private void enterLoose(Session session, DetectionBox ball, int frame) {
        session.phase = Phase.LOOSE;
        session.looseStartFrame = frame;
        session.looseStartY = centerY(ball);
        session.lastHolderId = session.holder != null ? session.holder.trackId() : session.lastHolderId;
        session.lastHolderBox = session.holder != null ? session.holder : session.lastHolderBox;
        session.holder = null;
        session.lastDistance = -1;
        session.growingFrames = 0;
        session.ballPath.clear();
        session.ballPath.add(ball);
    }

    private void enterShot(Session session, int frame) {
        session.phase = Phase.SHOT;
        session.shotStartFrame = frame;
        session.shotTime = frame / session.fps;
        session.shotPeakY = session.ballPath.isEmpty()
                ? session.looseStartY
                : centerY(session.ballPath.getLast());
    }

    private boolean yReducedSignificantly(Session session) {
        if (session.ballPath.size() < SHOT_RISE_MIN_POINTS) {
            return false;
        }
        DetectionBox last = session.ballPath.getLast();
        return session.looseStartY - centerY(last) >= SHOT_RISE_PX;
    }

    private void finishShot(Session session, int finalFrame) {
        DetectionBox shooterBox = session.lastHolderBox;
        int shooterId = session.lastHolderId;
        if (shooterBox == null || session.hoop == null || shooterId < 0) {
            resetToPossessed(session);
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
            creditStats(session, credited);
        }
        resetToPossessed(session);
    }

    private void creditStats(Session session, Shot shot) {
        MutableStats shooter = session.stats.computeIfAbsent(shot.shooterTrackId(), id -> new MutableStats());
        shooter.shots.add(shot);
        if (shot.assist() && shot.passerTrackId() >= 0) {
            session.stats.computeIfAbsent(shot.passerTrackId(), id -> new MutableStats()).assists++;
        }
    }

    private void resetToPossessed(Session session) {
        session.phase = Phase.POSSESSED;
        session.holder = null;
        session.lastDistance = -1;
        session.growingFrames = 0;
        session.ballPath.clear();
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
        DetectionBox holder;
        DetectionBox lastHolderBox;
        final List<DetectionBox> ballPath = new ArrayList<>();
        final List<Shot> shots = new ArrayList<>();
        final Map<Integer, MutableStats> stats = new HashMap<>();

        double fps = 30.0;
        double shotTime = 0.0;
        double lastDistance = -1;
        double looseStartY;
        double shotPeakY = Double.MAX_VALUE;
        int frame;
        int growingFrames;
        int lastHolderId = -1;
        int lastPasserId = -1;
        int lastPassFrame = -1;
        int looseStartFrame;
        int shotStartFrame;
        int assistWindowFrames = 90;
        int shotTimeoutFrames = 60;
    }
}
