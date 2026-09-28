package com.turbo.backend_analytics.component;

import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Shot;
import com.turbo.backend_analytics.util.TimeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.turbo.backend_analytics.util.GeometryUtil.centerY;
import static com.turbo.backend_analytics.util.GeometryUtil.findHighestConfidence;

@Component
public class ShotPhysicsEngine {

    public record ShotFlight(int endFrame, List<DetectionBox> ballPath) {}

    private static final int BALL = 0;
    private static final int HOOP = 1;

    @Autowired
    private TrajectoryMath trajectoryMath;

    public ShotFlight analyzeFlight(
            DetectionBox hoop,
            Map<Integer, List<DetectionBox>> frames,
            int startFrame,
            int maxFrame,
            List<DetectionBox> alreadyTracked,
            DetectionBox shooter
    ) {
        List<DetectionBox> path = new ArrayList<>();
        if (alreadyTracked != null) {
            for (DetectionBox tracked : alreadyTracked) {
                if (isOnShotPath(tracked, shooter)) {
                    path.add(tracked);
                }
            }
        }
        DetectionBox liveHoop = hoop;
        boolean reachedHoopHeight = false;
        double peakY = Double.MAX_VALUE;
        DetectionBox lastBall = path.isEmpty() ? null : path.getLast();
        int endFrame = startFrame;

        for (int f = startFrame; f <= maxFrame; f++) {
            List<DetectionBox> boxes = frames.getOrDefault(f, List.of());
            DetectionBox hoopNow = findHighestConfidence(boxes, HOOP);
            if (hoopNow != null) {
                liveHoop = hoopNow;
            }
            DetectionBox ball = findHighestConfidence(boxes, BALL);
            if (ball == null) {
                continue;
            }
            // Screen Y grows downward. Keep a ball only above 120% of the last possessed shooter Y.
            if (isOnShotPath(ball, shooter) && (lastBall == null || ball.frameIndex() != lastBall.frameIndex())) {
                path.add(ball);
            }

            double y = centerY(ball);
            peakY = Math.min(peakY, y);
            double hoopBottom = liveHoop != null ? liveHoop.y() + liveHoop.height() : y;
            if (y <= hoopBottom) {
                reachedHoopHeight = true;
            }

            boolean falling = lastBall != null && y > centerY(lastBall) + 2.0;
            boolean belowHoop = y > hoopBottom;
            lastBall = ball;
            endFrame = f;

            if (reachedHoopHeight && falling && belowHoop) {
                break;
            }
            if (!reachedHoopHeight && falling && y > peakY + 12.0) {
                break;
            }
        }

        return new ShotFlight(endFrame, path);
    }

    private static boolean isOnShotPath(DetectionBox ball, DetectionBox shooter) {
        if (ball == null) {
            return false;
        }
        if (shooter == null) {
            return true;
        }
        // 120% of the last possessed shooter bounding box
        double limit = shooter.y() * 1.25;
        return centerY(ball) <= limit;
    }

    // Updates: Shot's shooter's data and make probability of the shot
    public Optional<Shot> generateShot(DetectionBox hoop, DetectionBox shooter, List<DetectionBox> ballPath, double currentShotTime, int frameIndex){
        if (shooter == null || hoop == null)
            return Optional.empty();

        double makeProbability = evaluateMake(hoop, shooter, ballPath);
        String shotTime = TimeUtil.formatTime(currentShotTime);
        boolean isMake = makeProbability > 0.7;

        Shot finishedShot = new Shot(shooter, makeProbability, shotTime, frameIndex, isMake);
        return Optional.of(finishedShot);
    }

    // Returns: calculated probability of a make shot
    public double evaluateMake(DetectionBox hoop, DetectionBox shooter, List<DetectionBox> ballPath){
        if (hoop == null || shooter == null || ballPath.size() < 3) return 0.0;

        // Defines Backboard area
        double bbMinX = hoop.x() - (hoop.width() * 1.5);
        double bbMaxX = hoop.x() + (hoop.width() * 2.5);
        double bbMaxY = hoop.y();                            // The lowest rim level
        double bbMinY = hoop.y() - (hoop.height() * 3.0);   // Top of the backboard

        int bounceIndex = -1;

        // Scan the path for a sudden change in horizontal speed inside the Backboard Zone
        for (int i = 2; i < ballPath.size(); i++) {
            DetectionBox past = ballPath.get(i - 2);
            DetectionBox prev = ballPath.get(i - 1);
            DetectionBox curr = ballPath.get(i);

            boolean inBackboardZone = (curr.x() >= bbMinX && curr.x() <= bbMaxX) &&
                    (curr.y() >= bbMinY && curr.y() <= bbMaxY);

            if (inBackboardZone) {
                double oldSpeedX = prev.x() - past.x();
                double newSpeedX = curr.x() - prev.x();
                double oldSpeedY = prev.y() - past.y();
                double newSpeedY = curr.y() - prev.y();

                // ADDED FIX: Also checks for sudden loss of Y momentum (Bank shots don't always reverse X)
                boolean hitGlass = (oldSpeedY < -2.0 && newSpeedY > 1.0) || 
                                   (Math.abs(newSpeedX - oldSpeedX) > Math.max(curr.width() * 0.1, 1.0));
                
                if (hitGlass) {
                    bounceIndex = i;
                    break;
                }
            }
        }

        List<DetectionBox> mathPath = ballPath;
        // Calculate the center of the hoop
        double hoopCenterX = hoop.x() + (hoop.width() / 2.0);
        double hoopCenterY = hoop.y() + (hoop.height() / 2.0);
        double predictedX;

        // If it's a backboard shot - use the ball coordinates after the collision
        if (bounceIndex != -1) {
            if (ballPath.size() - bounceIndex >= 3) {
                mathPath = ballPath.subList(bounceIndex, ballPath.size());
                DetectionBox bounceBox = ballPath.get(bounceIndex);
                double bounceX = bounceBox.x() + (bounceBox.width() / 2.0);
                predictedX = trajectoryMath.predictShotBallX(mathPath, hoopCenterY, bounceX);
            } else {
                predictedX = ballPath.getLast().x();
            }
        } else {
            predictedX = trajectoryMath.predictShotBallX(mathPath, hoopCenterY, shooter.x());
        }

        // Find the x value of the ball when reaches hoop Y coordinate
        double bestObservedX = -1; // Flag to check if we found a valid downward-moving frame
        double minDistanceToCenter = Double.MAX_VALUE;

        for (int i = 0; i < ballPath.size(); i++) {
            DetectionBox ball = ballPath.get(i);
            double ballCenterX = ball.x() + (ball.width() / 2.0);
            double ballCenterY = ball.y() + (ball.height() / 2.0);

            // Only analyze frames near or below the top of the rim
            if (ballCenterY >= (hoop.y() - hoop.height()/3.0)) {
                
                // --- ADDED GUARD: Z-Axis Parallax Guard ---
                // If the ball width is abnormally large/small compared to the hoop, 
                // it is an optical illusion falling between the camera and the rim.
                double scaleRatio = ball.width() / hoop.width();
                if (scaleRatio > 0.85 || scaleRatio < 0.30) {
                    continue; // Skip this frame
                }
                // ------------------------------------------

                double diffX = Math.abs(ballCenterX - hoopCenterX);
                double diffY = Math.abs(ballCenterY - hoopCenterY);
                double distanceToCenter = (diffX * diffX) + (diffY * diffY);

                if (distanceToCenter < minDistanceToCenter) {

                    // Check frames after this candidate to ensure the ball is NOT bouncing upward
                    boolean goesUpAfter = false;
                    int checkLimit = Math.min(ballPath.size(), i + 3); // Look up to 3 frames ahead

                    for (int j = i + 1; j < checkLimit; j++) {
                        DetectionBox nextBall = ballPath.get(j);
                        DetectionBox prevBall = ballPath.get(j - 1);

                        // Screen Y: smaller value means higher up
                        if (nextBall.y() < prevBall.y() - 4.0) {
                            goesUpAfter = true;
                            break;
                        }
                    }

                    // If it passed the look-ahead test, accept it as our best frame
                    if (!goesUpAfter) {
                        minDistanceToCenter = distanceToCenter;
                        bestObservedX = ballCenterX;
                    }
                }
            }
        }

        // UPDATED MATH: Flattened Probability Curve using Normalized Ratios
        double hoopRadius = hoop.width() / 2.0;
        double visualProbability;
        
        if (bestObservedX == -1) {
            visualProbability = 0.0; // No valid downward path found (bounced out, missed entirely, or illusion)
        } else {
            double visualDistance = Math.abs(hoopCenterX - bestObservedX);
            double visualRatio = visualDistance / hoopRadius;
            visualProbability = 1.0 - ((visualRatio * visualRatio) / 4.0);
            visualProbability = Math.min(1.0, Math.max(0.0, visualProbability));
        }
        
        if (predictedX == -1) return visualProbability;

        // UPDATED MATH: Flattened Probability Curve for Predicted Path
        double mathDistance = Math.abs(hoopCenterX - predictedX);
        double mathRatio = mathDistance / hoopRadius;
        double probability = 1.0 - ((mathRatio * mathRatio) / 4.2);
        probability = Math.min(1.0, Math.max(0.0, probability));

        return (probability * 0.4) + (visualProbability * 0.6);
    }
}
