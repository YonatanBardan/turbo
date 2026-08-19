package com.turbo.backend_analytics.component;

import com.turbo.backend_analytics.component.TrajectoryMath;
import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Shot;
import com.turbo.backend_analytics.dto.TrackingResponse;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.stream.Collectors;


@Component
public class ShotTracker {

    @Autowired
    private TrajectoryMath trajectoryMath;
    private static final int BALL = 0;
    private static final int HOOP = 2;
    private static final int SHOOTER = 3;
    private enum State {WAITING, VERIFYING, TRACKING}

    // Main:
    // Receives: Unorganized list of DetectionBoxes of a video
    // Returns: A list of "Shot" that accrued in the video
    public List<Shot> extractAllShots(List<DetectionBox> detections, double fps){

        TrackingSession session = new TrackingSession();
        session.fps = fps;
        session.hoop = findClass(detections, HOOP);
        if (session.hoop == null)
            return session.finishedShots;

        // Hashing the DetectionBox List by every Box frame index
        Map<Integer, List<DetectionBox>> frames = detections.stream().
                collect(Collectors.groupingBy(DetectionBox::frameIndex));

        int maxFrame = frames.keySet().stream().max(Integer::compareTo).orElse(0);

        // Loop through all frames
        for (int i = 0; i <= maxFrame; i++) {

            List<DetectionBox> currentBoxes = frames.getOrDefault(i, new ArrayList<>());
            DetectionBox currentHoop = findClass(currentBoxes, HOOP);
            if (currentHoop != null)
                session.hoop = currentHoop;

            // Routing the current state into it's suit handler
            switch (session.state) {
                case WAITING -> handleWaitingState(currentBoxes, session);
                case VERIFYING -> handleVerifyingState(currentBoxes, session);
                case TRACKING -> handleTrackingState(currentBoxes, session);
            }
        }

        return session.finishedShots;
    }

    // -----------------------------------------------------
    // Handlers functions
    // -----------------------------------------------------
    // 1) Searching for a Shooter detection in the list received and updates session accordingly
    private void handleWaitingState(List<DetectionBox> boxes, TrackingSession session){
        DetectionBox shooter = findClass(boxes, SHOOTER);

        if (shooter != null){
            session.state = State.VERIFYING;
            session.shooter = shooter;
            session.ballPath.clear();
        }
    }

    // 2) Detect whether the ball is increasing it's y coordinate value
    private void handleVerifyingState(List<DetectionBox> boxes, TrackingSession session){
        DetectionBox ball = findClass(boxes, BALL);

        if (ball != null){

            // 1 - check if the shooter / ball boxes are overlap
            if (isOverLap(ball, session.shooter))
                return;

            session.ballPath.add(ball);

            // 2 - check for "ball going up" after released
            if (session.ballPath.size() == 2){
                if (ball.y() < session.ballPath.getFirst().y()) {
                    session.state = State.TRACKING;
                    session.currentShotTime = ball.frameIndex() / session.fps;
                }
                else {
                    session.state = State.WAITING;
                    session.ballPath.clear();
                }
            }
        }
    }

    // 3) Adding ball detections of the "shot" phase and declare a finished shot
    private void handleTrackingState(List<DetectionBox> boxes, TrackingSession session){

        DetectionBox ball = findClass(boxes, BALL);
        if (ball != null)
            session.ballPath.add(ball);

        // If Ball drops bellow hoopY calculate the Shot params and reset state
        if (!session.ballPath.isEmpty()){
            double lastBallY = session.ballPath.getLast().y();
            boolean bellowHoop = lastBallY > session.hoop.y();
            if (bellowHoop){
                finalizeShot(session, session.ballPath.getLast().frameIndex());
                session.state = State.WAITING;
                session.ballPath.clear();
                session.shooter = null;
            }
        }
    }

    // -----------------------------------------------------
    // Helper functions
    // -----------------------------------------------------
    // Returns: true if the 2 bounding boxes are overlapping
    private boolean isOverLap(DetectionBox ball, DetectionBox shooter){
        boolean notOverLap =
                (ball.x() + ball.width() < shooter.x()) ||      // Ball not overlap from left
                (ball.x() > shooter.x() + shooter.width()) ||   // Ball not overlap from right
                (ball.y() + ball.height() < shooter.y()) ||     // Ball not overlap from top
                (ball.y() > shooter.y() + shooter.height());    // Ball not overlap from bottom

        return !notOverLap;    // If notOverLap is true then we need to return false
    }

    // Updates: Shot's shooter's data and make probability of the shot
    private void finalizeShot(TrackingSession session, int frameIndex){
        if (session.shooter == null || session.hoop == null)
            return;

        double makeProbability = evaluateMake(session);
        String shotTime = formatTime(session.currentShotTime);
        boolean isMake = makeProbability > 0.65;

        Shot currShot = new Shot(session.shooter, makeProbability, shotTime, frameIndex, isMake);
        session.finishedShots.add(currShot);
    }

    // Returns: calculated probability of a make shot
    private double evaluateMake(TrackingSession session){
        if (session.hoop == null || session.shooter == null) return 0.0;

        // Calculate the center of the hoop
        double hoopCenterX = session.hoop.x() + (session.hoop.width() / 2.0);
        double hoopCenterY = session.hoop.y() + (session.hoop.height() / 2.0);

        double predictedX = trajectoryMath.predictShotBallX(session.ballPath, hoopCenterY, session.shooter.x());
        if (predictedX == -1)
            return 0.0;

        // Probability calculated by "Least Squared" which: (0, 0.95), (15, 0.7), (30, 0.5), (50, 0.05)
        double distance = Math.abs(hoopCenterX - predictedX);
        double probability = (-0.0001168 * distance * distance) - (0.01229 * distance) + 0.957;

        return Math.max(0.0, probability);
    }

    // Returns: First DetectionBox that contains a box with the requested id
    private DetectionBox findClass(List<DetectionBox> boxes, int classId){
        return boxes.stream()
                .filter(box -> box.classId() == classId)
                .findFirst().orElse(null);
    }

    // Returns: Formatted mm:ss string ("01:12")
    private String formatTime(double totalSec){
        int secInt = (int) totalSec;
        int minutes = secInt / 60;
        int seconds = secInt % 60;

        return String.format("%02d:%02d", minutes, seconds);
    }


    // -----------------------------------------------------
    // Helper inner class
    private static class TrackingSession {

        // Controlling the current function phase and objects being used at the moment.
        State state = State.WAITING;                    // State
        DetectionBox hoop = null;                       // Updated hoop position
        DetectionBox shooter = null;                    // Location of the shooter
        List<DetectionBox> ballPath = new ArrayList<>();      // Ball's DetectionBoxes List since detecting a shooter
        List<Shot> finishedShots = new ArrayList<>();   // Final shots instances in the processed video
        double fps = 30.0;
        double currentShotTime = 0.0;
    }
}
