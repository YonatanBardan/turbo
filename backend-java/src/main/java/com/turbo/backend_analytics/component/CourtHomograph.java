package com.turbo.backend_analytics.component;

import com.turbo.backend_analytics.dto.Court.CourtKeypoints;
import com.turbo.backend_analytics.dto.Court.Point;
import com.turbo.backend_analytics.dto.DetectionBox;
import org.apache.commons.math3.linear.Array2DRowRealMatrix;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.DecompositionSolver;
import org.apache.commons.math3.linear.LUDecomposition;
import org.apache.commons.math3.linear.RealMatrix;
import org.apache.commons.math3.linear.RealVector;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CourtHomograph {

    private final double baseline_size = 1500;  // Fiba standard baseline size in pixels 100 cm = 10 pixels
    private final double sideline_size = 1400;  // Fiba standard sideline size in pixels 100 cm = 10 pixels

    private final Point paintBaseTopLeft = new Point(505, 0);
    private final Point paintBaseTopRight = new Point(995, 0);
    private final Point paintFreeRight = new Point(995, 580);
    private final Point paintFreeLeft = new Point(505, 580);

    private final Point [] CourtCornersPoints = {
        new Point(0, 0),                          // Top left corner
        new Point(baseline_size, 0),
        new Point(baseline_size, sideline_size),
        new Point(0, sideline_size)
    };

    public Point calculateShotLocation(DetectionBox shooter, List<CourtKeypoints> courtKeypoints) {
        if (courtKeypoints == null || courtKeypoints.isEmpty() || shooter == null)
            return null;

        CourtKeypoints curr_court_keypoints = findClosestCourtKeypoints(courtKeypoints, shooter.frameIndex());
        if (curr_court_keypoints == null || curr_court_keypoints.corners().size() != 4)
            return null; // Failed to detect court keypoints
    
        Point shooterLocation = new Point(shooter.x() +shooter.width()/2, shooter.y() +shooter.height());  // player's center feet location
        // Point[] CourtPaintCorners = sortCorners(closestKeypoints.corners()); -- Sorting if YOLO missing on sorting corners

        double[] HomoMatrix = createHomographyMatrix(curr_court_keypoints.corners().toArray(Point[]::new));
        return projectPoint(shooterLocation, HomoMatrix);
    }


    private static CourtKeypoints findClosestCourtKeypoints(List<CourtKeypoints> courtKeypoints, int frameIndex) {
        int remains = frameIndex % 30;
        return courtKeypoints.get((frameIndex - remains) / 30); // Assumes that every frame has detected court keypoints object
    }

    private double[] createHomographyMatrix(Point[] videoCorners) {
        Point[] paintCorners = {paintBaseTopLeft, paintBaseTopRight, paintFreeRight, paintFreeLeft};
        double [][] coefficients = new double[8][8]; // A matrix
        double[] Answers = new double[8]; // B vector

        // Calculate: A * X = B,   X = [a, b, c, d, e, f, g, h]
        // mapped_to_x = ax + by + c - (mapped_to_x)gx - (mapped_to_x)hy
        // mapped_to_y = dx + ey + f - (mapped_to_y)gx - (mapped_to_y)hy

        for (int i = 0; i < 4; i++) {
            double frame_x = videoCorners[i].x();
            double frame_y = videoCorners[i].y();
            double mapped_to_x = paintCorners[i].x();
            double mapped_to_y = paintCorners[i].y();

            // X axis equation:
            coefficients[i*2][0] = frame_x;
            coefficients[i*2][1] = frame_y;
            coefficients[i*2][2] = 1;
            coefficients[i*2][3] = 0;
            coefficients[i*2][4] = 0;
            coefficients[i*2][5] = 0;
            coefficients[i*2][6] = -mapped_to_x * frame_x;
            coefficients[i*2][7] = -mapped_to_x * frame_y;
            Answers[i*2] = mapped_to_x;

            // Y axis equation:
            coefficients[i*2+1][0] = 0;
            coefficients[i*2+1][1] = 0;
            coefficients[i*2+1][2] = 0;
            coefficients[i*2+1][3] = frame_x;
            coefficients[i*2+1][4] = frame_y;
            coefficients[i*2+1][5] = 1;
            coefficients[i*2+1][6] = -mapped_to_y * frame_x;
            coefficients[i*2+1][7] = -mapped_to_y * frame_y;
            Answers[i*2+1] = mapped_to_y;
        }

        // Wraps the arrays into Apache objects -appachy works by subtracting rows from each other
        // to create 2 triangular matrices to create A = L * U equation
        RealMatrix A = new Array2DRowRealMatrix(coefficients, false);  // Apache 2D array object / false means don't copy the array
        RealVector B = new ArrayRealVector(Answers, false);            // Apache vector object / false means don't copy the array

        DecompositionSolver solver = new LUDecomposition(A).getSolver(); // LU Decomposition algorithm on matrix A to create L and U matrices
        RealVector X = solver.solve(B);                                  // Solves the system A * X = B and returns the result as a vector

        // Returns [a, b, c, d, e, f, g, h] result as an arrry
        return X.toArray();
    }

    private Point projectPoint(Point shooterLocation, double[] homoMatrix) {
        double x = shooterLocation.x(), y = shooterLocation.y();
        double a = homoMatrix[0], b = homoMatrix[1], c = homoMatrix[2],
            d = homoMatrix[3], e = homoMatrix[4], f = homoMatrix[5],
            g = homoMatrix[6], h = homoMatrix[7];

        // [x']    [a, b, c]   [x]
        // |y'| =  |d, e, f| * |y|
        // [w]     [g, h, 1]   [1]
        double w = g * x + h * y + 1;

        double normalizedDepthX = (a * x + b * y + c) / w ;
        double normalizedDepthY = (d * x + e * y + f) / w ;

        return new Point(normalizedDepthX, normalizedDepthY);
    }
}