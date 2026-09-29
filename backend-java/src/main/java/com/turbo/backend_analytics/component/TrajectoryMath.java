package com.turbo.backend_analytics.component;

import com.turbo.backend_analytics.dto.DetectionBox;
import org.apache.commons.math3.fitting.PolynomialCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoints;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Component
public class TrajectoryMath {

    // Receives: A list of Ball detectionBox of a shot instance, hoop height and a shooter x.
    // Returns: A double of the ball projected x coordinate when reaching the hoop height coordinate.
    public double predictShotBallX (List<DetectionBox> ballPath, double hoopY, double shooterX){

        // if length of list is too small
        if (ballPath.size() < 3 )
            return - 1;

        WeightedObservedPoints obsY = new WeightedObservedPoints();   // (time, y) ball path as of time (frame index)
        WeightedObservedPoints obsX = new WeightedObservedPoints();   // (time, x) ball path as of time (frame index)

        int baseFrame = ballPath.get(0).frameIndex();   // Normalize the time to not reach negative values

        for (DetectionBox box : ballPath){
            double t = box.frameIndex() - baseFrame;
            obsY.add(box.confidence(), t, box.y());
            obsX.add(box.confidence(), t, box.x());
        }

        // Y Parabola as of frame index: Y(t) = a*t^2 + b*t + c
        PolynomialCurveFitter calcY = PolynomialCurveFitter.create(2);
        double[] coefY = calcY.fit(obsY.toList());

        double c = coefY[0];
        double b = coefY[1];
        double a = coefY[2];

        //  X Line as of frame index: X(t) = d*t + e 
        PolynomialCurveFitter calcX = PolynomialCurveFitter.create(1);
        double[] coefX = calcX.fit(obsX.toList());

        double e = coefX[0]; 
        double d = coefX[1]; // Slope

        // Solve when Y(t) = hoopY, ( a*t^2 + b*t + (c - hoopY) = 0 )
        double yDiff = c - hoopY;
        double discriminant = (b * b) - (4 * a * yDiff);

        if (discriminant < 0 )
            return - 1; // No solution - airball
        double t1 = (-b + Math.sqrt(discriminant)) / (2 * a);    // first solution
        double t2 = (-b - Math.sqrt(discriminant)) / (2 * a);    // second solution

        double latestTime = Math.max(t1, t2); // take the latest time between the two solutions

        return d * latestTime + e;            // return the x coordinate when the ball reaches the hoop height

    }

    /*
    private List<DetectionBox> filterBallPath(List<DetectionBox> ballPath) {
      
        // 1. Group and sort automatically using a TreeMap
        Map<Integer, List<DetectionBox>> frames = new TreeMap<>();
        for (DetectionBox box : ballPath) {
            frames.computeIfAbsent(box.frameIndex(), k -> new ArrayList<>()).add(box);   // if key not exists, create new list then add the box to it.
                                                                                         //  if exist, add the box to the list.
        }

        List<DetectionBox> cleanPath = new ArrayList<>();
        DetectionBox lastBall = null;

        // Loop through the chronologically sorted frames - because of the tree map
        for (List<DetectionBox> ballsInFrame : frames.values()) {
            DetectionBox bestBall = null;
            
            // If it's the first frame, take max confidence. Otherwise, the min distance.
            double bestScore = (lastBall == null) ? -1.0 : Double.MAX_VALUE;    // if () then do -1.0 if () is false do Double.MAX_VALUE

            for (DetectionBox candidate : ballsInFrame) {
                if (lastBall == null) {                          // First frame - pick the ball with the highest confidence
                    if (candidate.confidence() > bestScore) {   
                        bestScore = candidate.confidence();
                        bestBall = candidate;
                    }
                } else {                                         // Pick the ball with the shortest physical distance
                    double distance = Math.hypot(candidate.x() - lastBall.x(), candidate.y() - lastBall.y());  // hypot = square root of the sum of the squares of the arguments
                    if (distance < bestScore) {
                        bestScore = distance;
                        bestBall = candidate;
                    }
                }
            }
            
            cleanPath.add(bestBall);
            lastBall = bestBall;
        }
        
        return cleanPath;
    }*/
}