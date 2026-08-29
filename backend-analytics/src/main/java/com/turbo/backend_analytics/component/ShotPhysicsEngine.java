package com.turbo.backend_analytics.component;

import com.turbo.backend_analytics.dto.DetectionBox;
import com.turbo.backend_analytics.dto.Shot;
import com.turbo.backend_analytics.util.TimeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class ShotPhysicsEngine {

    @Autowired
    private TrajectoryMath trajectoryMath;

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
        double bbMaxY = hoop.y();           // The lowest rim level
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
                boolean reversed = (oldSpeedX * newSpeedX) < -4.0;            // Bounced backword from the collision
                double spikeThreshold = Math.max(curr.width() * 0.1, 1.0);   // X velocity changed drastically

                boolean collisionSpike = Math.abs(newSpeedX - oldSpeedX) > spikeThreshold;
                if (reversed || collisionSpike) {
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

        // If it's a bank shot - use the ball coordinates after the collision
        if (bounceIndex != -1) {
            if (ballPath.size() - bounceIndex >= 3) {
                mathPath = ballPath.subList(bounceIndex, ballPath.size());
                DetectionBox bounceBox = ballPath.get(bounceIndex);
                double bounceX = bounceBox.x() + (bounceBox.width() / 2.0);
                predictedX = trajectoryMath.predictShotBallX(mathPath, hoopCenterY, bounceX);
            } else {
                predictedX = ballPath.getLast().x();            }
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
            if (ballCenterY >= (hoop.y() - hoop.height())) {
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
                        if (nextBall.y() < prevBall.y() - 1.0) {
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

        double visualProbability;
        if (bestObservedX == -1) {
            visualProbability = 0.0; // No valid downward path found (bounced out or missed entirely)
        } else {
            double visualDistance = Math.abs(hoopCenterX - bestObservedX);
            visualProbability = (-0.0001168 * visualDistance * visualDistance) - (0.01229 * visualDistance) + 0.957;
            visualProbability = Math.max(0.0, visualProbability);
        }
        if (predictedX == -1) return visualProbability;

        // Probability calculated by "Least Squared" which: (0, 0.95), (15, 0.7), (30, 0.5), (50, 0.05)
        double distance = Math.abs(hoopCenterX - predictedX);
        double probability = (-0.0001168 * distance * distance) - (0.01229 * distance) + 0.957;
        probability = Math.max(0.0, probability);

        return (probability * 0.4) + (visualProbability * 0.6);
    }
}
