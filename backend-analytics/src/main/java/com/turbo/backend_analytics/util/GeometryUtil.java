package com.turbo.backend_analytics.util;

import com.turbo.backend_analytics.dto.DetectionBox;

import java.util.List;

public class GeometryUtil {

    // Returns: First DetectionBox that contains a box with the requested id
    public static DetectionBox findClass(List<DetectionBox> boxes, int classId){
        return boxes.stream()
                .filter(box -> box.classId() == classId)
                .findFirst().orElse(null);
    }

    // Returns: true if the 2 bounding boxes are overlapping
    public static boolean isOverLap(DetectionBox ball, DetectionBox shooter){
        boolean notOverLap =
                (ball.x() + ball.width() < shooter.x()) ||      // Ball not overlap from left
                        (ball.x() > shooter.x() + shooter.width()) ||   // Ball not overlap from right
                        (ball.y() + ball.height() < shooter.y()) ||     // Ball not overlap from top
                        (ball.y() > shooter.y() + shooter.height());    // Ball not overlap from bottom

        return !notOverLap;    // If notOverLap is true then we need to return false
    }

    // Checks if the ball is in the "Release zone"
    public static boolean isNearShooter(DetectionBox ball, DetectionBox shooter){
        // Expand the shooter's bounding box by their own width / height
        double marginX = shooter.width() * 0.2;
        double marginY = shooter.height() * 0.5;

        boolean nearX = ball.x() >= (shooter.x() - marginX) && ball.x() <= (shooter.x() + shooter.width() + marginX);
        boolean nearY = ball.y() >= (shooter.y() - marginY) && ball.y() <= (shooter.y() + shooter.height());

        return nearX && nearY;
    }

}
