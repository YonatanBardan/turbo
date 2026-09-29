package com.turbo.backend_analytics.component.ball;

import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Player.PlayerStats;
import com.turbo.backend_analytics.dto.Shot.Shot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.turbo.backend_analytics.util.GeometryUtil.centerY;

class BallSession {

    static final int BALL = 0;
    static final int HOOP = 1;
    static final int PLAYER = 2;

    static final int SHOT_RISE_MIN_POINTS = 3;
    static final double SHOT_RISE_PX = 18.0;
    static final double SHOT_SHOULDER_RATIO = 0.30;
    static final double RELEASE_TOP_RATIO = 0.8;
    static final int LOOSE_SAME_HOLDER_DELAY = 2;
    static final int MISSING_BALL_PASS_FRAMES = 2;
    static final int SHOT_LOOKBACK_FRAMES =35;
    static final double ASSIST_WINDOW_SECONDS = 3.0;
    static final int TAKEOFF_LOOKBACK_FRAMES = 20;   // frames before the release to search for the take-off box
    static final int DYNAMIC_LOOKBACK_FRAMES = 13;    // frames before the shot to search for the dynamic lookback

    enum Phase { POSSESSED, LOOSE, SHOT }

    Phase phase = Phase.POSSESSED;
    DetectionBox hoop;
    DetectionBox holder;
    DetectionBox lastHolderBox;
    final List<DetectionBox> ballPath = new ArrayList<>();
    final List<Shot> shots = new ArrayList<>();
    final Map<Integer, MutableStats> stats = new HashMap<>();
    final Map<Integer, DetectionBox> possessionByFrame = new HashMap<>();

    double fps = 30.0;
    double shotTime = 0.0;
    double looseStartY;
    int frame;
    int lastHolderId = -1;
    int framesWithoutBall = 0;
    int lastPasserId = -1;
    int lastPassFrame = -1;
    int looseStartFrame = -1;
    int shotStartFrame = -1;
    int assistWindowFrames = 90;
    int resumeAt = -1;
    boolean releasedFromTop = false;

    void enterPossessed(DetectionBox holder, boolean fromPass) {
        if (fromPass && lastHolderId >= 0 && holder.trackId() != lastHolderId) {
            lastPasserId = lastHolderId;
            lastPassFrame = frame;
        }
        phase = Phase.POSSESSED;
        this.holder = holder;
        lastHolderId = holder.trackId();
        lastHolderBox = holder;
        framesWithoutBall = 0;
        releasedFromTop = false;
        ballPath.clear();
    }

    void markPossessedRelease(DetectionBox ball, DetectionBox player) {
        if (ball == null || player == null) {
            return;
        }
        // Top 0.95 of the player height, measured up from the feet, and anything above the head.
        double topLine = player.y() + player.height() * (1.0 - RELEASE_TOP_RATIO);
        releasedFromTop = centerY(ball) <= topLine;
    }

    void enterLoose(DetectionBox ball, int frame) {
        phase = Phase.LOOSE;
        looseStartFrame = frame;
        lastHolderId = holder != null ? holder.trackId() : lastHolderId;
        lastHolderBox = holder != null ? holder : lastHolderBox;
        holder = null;
        framesWithoutBall = 0;
        ballPath.clear();
        if (ball != null) {
            looseStartY = centerY(ball);
            ballPath.add(ball);
        }
    }

    void notePossession(int frame, DetectionBox holderBox) {
        if (holderBox != null && holderBox.trackId() >= 0) {
            possessionByFrame.put(frame, holderBox);
        }
    }

    void enterShot(int frame) {
        phase = Phase.SHOT;
        shotTime = frame / fps;
        shotStartFrame = frame;
    }

    int shotCountStartFrame() {
        if (looseStartFrame >= 0) {
            return looseStartFrame;
        }
        return shotStartFrame;
    }

    Map<Integer, Integer> possessionCounts(int shotFrame) {
        int from = Math.max(0, shotFrame - SHOT_LOOKBACK_FRAMES);
        Map<Integer, Integer> counts = new HashMap<>();
        int runId = -1;
        int runLength = 0;
        int previousFrame = -1;
        for (int f = from; f < shotFrame; f++) {
            DetectionBox box = possessionByFrame.get(f);
            boolean continues = box != null
                    && runLength > 0
                    && box.trackId() == runId
                    && previousFrame == f - 1;
            if (continues) {
                runLength++;
                previousFrame = f;
                continue;
            }
            if (runLength > 0) {
                counts.merge(runId, runLength, Integer::sum);
            }
            if (box == null || box.trackId() < 0) {
                runId = -1;
                runLength = 0;
                previousFrame = -1;
                continue;
            }
            runId = box.trackId();
            runLength = 1;
            previousFrame = f;
        }
        if (runLength > 0) {
            counts.merge(runId, runLength, Integer::sum);
        }
        return counts;
    }

    String possessionSpread(int shotFrame) {
        List<Map.Entry<Integer, Integer>> rows = new ArrayList<>(possessionCounts(shotFrame).entrySet());
        rows.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            if (byCount != 0) {
                return byCount;
            }
            return Integer.compare(a.getKey(), b.getKey());
        });
        StringBuilder text = new StringBuilder();
        for (Map.Entry<Integer, Integer> row : rows) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append("id ").append(row.getKey()).append(": ").append(row.getValue());
        }
        return text.toString();
    }

    int resolveShotShooter(int shotFrame) {
        Map<Integer, Integer> counts = possessionCounts(shotFrame);
        int bestCount = 0;
        for (int count : counts.values()) {
            if (count > bestCount) {
                bestCount = count;
            }
        }
        if (bestCount == 0) {
            return lastHolderId;
        }
        int from = Math.max(0, shotFrame - SHOT_LOOKBACK_FRAMES);
        for (int f = shotFrame - 1; f >= from; f--) {
            DetectionBox box = possessionByFrame.get(f);
            if (box != null && counts.getOrDefault(box.trackId(), 0) == bestCount) {
                return box.trackId();
            }
        }
        return lastHolderId;
    }

    DetectionBox resolveShotShooterBox(int shotFrame, int shooterId) {
        for (int f = shotFrame - 1; f >= 0; f--) {
            DetectionBox box = possessionByFrame.get(f);
            if (box != null && box.trackId() == shooterId) {
                return box;
            }
        }
        return lastHolderBox;
    }

    // The release box is taken at the top of the jump, so its feet point projects too deep on the
    // court (the homography assumes the feet are on the floor). Within the last TAKEOFF_LOOKBACK_FRAMES
    // before the release, the frame where the shooter's bottom edge is lowest on screen is the last
    // ground contact before take-off - a jumper lands right where they took off, so map from there.
    DetectionBox resolveShotGroundBox(int shotFrame, int shooterId) {
        DetectionBox grounded = null;
        double lowestFeetY = Double.NEGATIVE_INFINITY;
        int from = Math.max(0, shotFrame - TAKEOFF_LOOKBACK_FRAMES);

        // Check if the player moved fast to better detect the shooter feet location.
        DetectionBox currentBox = possessionByFrame.get(shotFrame - 1);
        DetectionBox pastBox = null;
        // Find the player's location ~10 frames ago to measure speed
        for (int f = shotFrame - 2; f >= Math.max(0, shotFrame - 10); f--) {
            DetectionBox b = possessionByFrame.get(f);
            if (b != null && b.trackId() == shooterId) {
                pastBox = b;
            }
        }

        // If the player moved fast, use the dynamic lookback frames to look back less frames.
        if (pastBox != null && currentBox != null) {
            double deltaX = currentBox.x() - pastBox.x();
            double deltaY = currentBox.y() - pastBox.y();
            double speed = Math.hypot(deltaX, deltaY);
            if (speed / (currentBox.frameIndex() - pastBox.frameIndex()) > 6.0) {
                from = Math.max(0, shotFrame - DYNAMIC_LOOKBACK_FRAMES);
            }
        }

        for (int f = shotFrame - 1; f >= from; f--) {
            DetectionBox box = possessionByFrame.get(f);
            if (box == null || box.trackId() != shooterId) {
                continue;
            }
            double feetY = box.y() + box.height();  // screen Y grows downward - larger means closer to the floor
            if (feetY > lowestFeetY) {
                lowestFeetY = feetY;
                grounded = box;
            }
        }
        return grounded != null ? grounded : resolveShotShooterBox(shotFrame, shooterId);
    }

    String overlayLabel() {
        return switch (phase) {
            case POSSESSED -> holder != null && holder.trackId() >= 0
                    ? "ballstate = possessed by player " + holder.trackId()
                    : "ballstate = possessed";
            case LOOSE -> lastHolderId >= 0
                    ? "ballstate = loose by player " + lastHolderId
                    : "ballstate = loose";
            case SHOT -> lastHolderId >= 0
                    ? "shot by id " + lastHolderId
                    : "shot";
        };
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
