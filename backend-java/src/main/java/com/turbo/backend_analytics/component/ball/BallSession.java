package com.turbo.backend_analytics.component.ball;

import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.PlayerStats;
import com.turbo.backend_analytics.dto.Shot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.turbo.backend_analytics.util.GeometryUtil.centerY;

class BallSession {

    static final int BALL = 0;
    static final int HOOP = 1;
    static final int PLAYER = 2;

    static final int RELEASE_GROW_FRAMES = 2;
    static final double DISTANCE_GROW_PX = 3.0;
    static final int SHOT_RISE_MIN_POINTS = 3;
    static final double SHOT_RISE_PX = 18.0;
    static final double SHOT_SHOULDER_RATIO = 0.30;
    static final int LOOSE_SAME_HOLDER_DELAY = 2;
    static final double ASSIST_WINDOW_SECONDS = 3.0;

    enum Phase { POSSESSED, LOOSE, SHOT }

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
    int frame;
    int growingFrames;
    int lastHolderId = -1;
    int lastPasserId = -1;
    int lastPassFrame = -1;
    int looseStartFrame;
    int assistWindowFrames = 90;
    int resumeAt = -1;

    void enterPossessed(DetectionBox holder, boolean fromPass) {
        if (fromPass && lastHolderId >= 0 && holder.trackId() != lastHolderId) {
            lastPasserId = lastHolderId;
            lastPassFrame = frame;
        }
        phase = Phase.POSSESSED;
        this.holder = holder;
        lastHolderId = holder.trackId();
        lastHolderBox = holder;
        lastDistance = -1;
        growingFrames = 0;
        ballPath.clear();
    }

    void enterLoose(DetectionBox ball, int frame) {
        phase = Phase.LOOSE;
        looseStartFrame = frame;
        lastHolderId = holder != null ? holder.trackId() : lastHolderId;
        lastHolderBox = holder != null ? holder : lastHolderBox;
        holder = null;
        lastDistance = -1;
        growingFrames = 0;
        ballPath.clear();
        if (ball != null) {
            looseStartY = centerY(ball);
            ballPath.add(ball);
        }
    }

    void enterShot(int frame) {
        phase = Phase.SHOT;
        shotTime = frame / fps;
    }

    void creditStats(Shot shot) {
        MutableStats shooter = stats.computeIfAbsent(shot.shooterTrackId(), id -> new MutableStats());
        shooter.shots.add(shot);
        if (shot.assist() && shot.passerTrackId() >= 0) {
            stats.computeIfAbsent(shot.passerTrackId(), id -> new MutableStats()).assists++;
        }
    }

    Map<Integer, PlayerStats> freezeStats() {
        Map<Integer, PlayerStats> frozen = new HashMap<>();
        for (Map.Entry<Integer, MutableStats> entry : stats.entrySet()) {
            frozen.put(entry.getKey(), new PlayerStats(entry.getValue().assists, List.copyOf(entry.getValue().shots)));
        }
        return frozen;
    }

    static class MutableStats {
        int assists;
        final List<Shot> shots = new ArrayList<>();
    }
}
