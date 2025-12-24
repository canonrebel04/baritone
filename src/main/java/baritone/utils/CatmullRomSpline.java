package baritone.utils;

import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

public class CatmullRomSpline {

    /**
     * Interpolates a path using Catmull-Rom splines.
     * 
     * @param points The control points (path nodes).
     * @param pointsPerSegment Number of interpolated points between each pair of nodes.
     * @return A list of smooth points.
     */
    public static List<Vec3> interpolate(List<Vec3> points, int pointsPerSegment) {
        List<Vec3> smoothPath = new ArrayList<>();
        
        if (points.size() < 2) {
            return points;
        }

        // We need at least 4 points for the calculation. 
        // Duplicate start and end points to act as phantom control points.
        List<Vec3> expandedPoints = new ArrayList<>(points.size() + 2);
        expandedPoints.add(points.get(0)); // Phantom start
        expandedPoints.addAll(points);
        expandedPoints.add(points.get(points.size() - 1)); // Phantom end

        for (int i = 0; i < expandedPoints.size() - 3; i++) {
            Vec3 p0 = expandedPoints.get(i);
            Vec3 p1 = expandedPoints.get(i + 1);
            Vec3 p2 = expandedPoints.get(i + 2);
            Vec3 p3 = expandedPoints.get(i + 3);

            // For the very last segment, we might want to include t=1.0 to close the gap perfectly
            // But usually the next segment starts at t=0 which is the same point.
            for (int j = 0; j < pointsPerSegment; j++) {
                double t = (double) j / pointsPerSegment;
                smoothPath.add(calculateCatmullRom(t, p0, p1, p2, p3));
            }
        }
        
        // Add the very last point
        smoothPath.add(points.get(points.size() - 1));

        return smoothPath;
    }

    private static Vec3 calculateCatmullRom(double t, Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3) {
        double t2 = t * t;
        double t3 = t2 * t;

        // Coefficients
        double c0 = -0.5 * t3 + t2 - 0.5 * t;
        double c1 = 1.5 * t3 - 2.5 * t2 + 1.0;
        double c2 = -1.5 * t3 + 2.0 * t2 + 0.5 * t;
        double c3 = 0.5 * t3 - 0.5 * t2;

        double x = c0 * p0.x + c1 * p1.x + c2 * p2.x + c3 * p3.x;
        double y = c0 * p0.y + c1 * p1.y + c2 * p2.y + c3 * p3.y;
        double z = c0 * p0.z + c1 * p1.z + c2 * p2.z + c3 * p3.z;

        return new Vec3(x, y, z);
    }
}
