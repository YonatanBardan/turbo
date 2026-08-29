package com.turbo.backend_analytics.component;

import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Shot;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.turbo.backend_analytics.util.GeometryUtil.*;


@Component
public class ShotTracker {

    @Autowired
    private ShotPhysicsEngine physicsEngine;
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

        // Add all shooters in the frame onto the list
        List<DetectionBox> currentShooters = new ArrayList<>();
        for (DetectionBox box : boxes) {
            if (box.classId() == SHOOTER) {
                currentShooters.add(box);
            }
        }

        // If no shooters at all were found, reset counter
        if (currentShooters.isEmpty()) {
            session.shooterFrameCount = 0;
            session.potentialShooter = null;
            return;
        }
        // If shooters is empty add the highest conf shooter
        if (session.shooterFrameCount == 0) {
            DetectionBox bestShooter = currentShooters.get(0);
            for (DetectionBox candidate : currentShooters){
                if (candidate.confidence() > bestShooter.confidence())
                    bestShooter = candidate;
            }
            session.potentialShooter = bestShooter;
            session.shooterFrameCount = 1;
            return;
        }

        DetectionBox matchedShooter = null;
        for (DetectionBox candidate : currentShooters) {
            if (isOverLap(candidate, session.potentialShooter)) {
                matchedShooter = candidate;
                break;
            }
        }

        if (matchedShooter != null) {
            session.potentialShooter = matchedShooter; // Update to their new slightly-moved box
            session.shooterFrameCount++;
            session.missedShooterFrames = 0;

            // If there are 3 shooters detected frames move to verifying
            if (session.shooterFrameCount >= 3) {
                session.state = State.VERIFYING;
                session.shooter = session.potentialShooter;
                session.ballPath.clear();

                session.shooterFrameCount = 0;
                session.potentialShooter = null;
            }
        }
        else {
            // The shooter didn't matched
            session.missedShooterFrames++;
            // If we miss the shooter for more than 1 frames
            if (session.missedShooterFrames > 1) {
                session.shooterFrameCount = 0;
                session.potentialShooter = null;
                session.missedShooterFrames = 0;
            }
        }
    }

    // 2) Detect whether the ball is increasing it's y coordinate value
    private void handleVerifyingState(List<DetectionBox> boxes, TrackingSession session){
        DetectionBox ball = findClass(boxes, BALL);

        if (ball != null){

            // Check if the first detected ball box is near the shooter - terminates rebounders and false shooter detections
            if (session.ballPath.isEmpty() && !isNearShooter(ball, session.shooter)) {
                session.state = State.WAITING;
                session.shooterFrameCount = 0;
                return;
            }

            // Check if the shooter / ball boxes are overlap
            if (isOverLap(ball, session.shooter))
                return;

            session.ballPath.add(ball);

            // Check for "ball going up" after released
            if (session.ballPath.size() == 8){
                if (ball.y() < session.ballPath.getFirst().y()) {
                    session.state = State.TRACKING;
                    session.currentShotTime = ball.frameIndex() / session.fps;
                }
                else {
                    session.state = State.WAITING;
                    session.ballPath.clear();
                    session.shooterFrameCount = 0;
                    session.potentialShooter = null;
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
            boolean bellowHoop = lastBallY > session.hoop.y() + session.hoop.height();
            if (bellowHoop){
                int finalFrame = session.ballPath.getLast().frameIndex();
                Optional<Shot> possibleShot = physicsEngine.generateShot(
                        session.hoop,
                        session.shooter,
                        session.ballPath,
                        session.currentShotTime,
                        finalFrame
                );
                possibleShot.ifPresent(shot -> session.finishedShots.add(shot));
                // Reset the state
                session.state = State.WAITING;
                session.ballPath.clear();
                session.shooter = null;
                session.shooterFrameCount = 0;
                session.potentialShooter = null;
            }
        }
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

        DetectionBox potentialShooter = null;
        int shooterFrameCount = 0;
        int missedShooterFrames = 0;
    }
}
