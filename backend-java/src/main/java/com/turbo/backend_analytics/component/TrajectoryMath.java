package com.turbo.backend_analytics.component;

import com.turbo.backend_analytics.dto.DetectionBox;
import org.apache.commons.math3.fitting.PolynomialCurveFitter;
import org.apache.commons.math3.fitting.WeightedObservedPoints;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class TrajectoryMath {


    // Receives: A list of Ball detectionBox of a shot instance, hoop height and a shooter x.
    // Returns: A double of the ball projected x coordinate when reaching the hoop height coordinate.
    public double predictShotBallX (List<DetectionBox> ballPath, double hoopY, double shooterX){

        // if length of list is too small
        if (ballPath.size() < 3 )
            return - 1;

        // Inserting all DetectionBoxes values into the apache's obs object.
        WeightedObservedPoints obs = new WeightedObservedPoints();
        for (DetectionBox box : ballPath)
            obs.add(box.confidence(), box.x(), box.y());

        // Calculating the projected polynom.
        PolynomialCurveFitter calculator = PolynomialCurveFitter.create(2);
        double [] coefficients = calculator.fit(obs.toList());

        double c = coefficients[0];
        double b = coefficients[1];
        double a = coefficients[2];

        // Solving - Y_(hoop) = ax^2 + bx + c
        double yDiff = c - hoopY;
        double discriminant = (b * b) - (4 * a * yDiff);

        if (discriminant < 0 )
            return - 1; // No solution - airball
        double x1 = (-b + Math.sqrt(discriminant)) / (2 * a);
        double x2 = (-b - Math.sqrt(discriminant)) / (2 * a);

        // Returns: the parabola farthest hoopY point intersection from the shooter x coordinate.
        double dist1 = Math.abs(x1 - shooterX);
        double dist2 = Math.abs(x2 - shooterX);

        return dist1 > dist2 ? x1 : x2;

    }
}
