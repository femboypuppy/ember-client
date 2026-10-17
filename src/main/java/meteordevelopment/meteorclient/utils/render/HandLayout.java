package meteordevelopment.meteorclient.utils.render;

import org.joml.Matrix4d;
import org.joml.Vector3d;

/** Hand View's R * S * T convention and the editor's projection math, without game state. */
public final class HandLayout {
    public enum Tool { Move, Rotate, Scale }
    public record Pose(double x, double y, double z, double rx, double ry, double rz, double sx, double sy, double sz) {
        public Vector3d position() { return new Vector3d(x, y, z); }
        public Vector3d rotation() { return new Vector3d(rx, ry, rz); }
        public Vector3d scale() { return new Vector3d(sx, sy, sz); }
        public static Pose of(Vector3d p, Vector3d r, Vector3d s) { return new Pose(p.x, p.y, p.z, r.x, r.y, r.z, s.x, s.y, s.z); }
        public static Pose identity() { return new Pose(0, 0, 0, 0, 0, 0, 1, 1, 1); }
    }
    public record Snapshot(Pose main, Pose off) {}

    /** Reflection in the center plane: M * (Rx Ry Rz S T) * M. Scale is copied, not negated. */
    public static Pose mirror(Pose p) { return new Pose(-p.x, p.y, p.z, p.rx, -p.ry, -p.rz, p.sx, p.sy, p.sz); }
    public static Matrix4d matrix(Pose p) { return rotationScale(p).translate(p.x, p.y, p.z); }
    private static Matrix4d rotationScale(Pose p) {
        return new Matrix4d().rotateXYZ(Math.toRadians(p.rx), Math.toRadians(p.ry), Math.toRadians(p.rz)).scale(p.sx, p.sy, p.sz);
    }

    public static double component(Pose p, Tool tool, int axis) {
        return switch (tool) { case Move -> p.position().get(axis); case Rotate -> p.rotation().get(axis); case Scale -> p.scale().get(axis); };
    }

    public static Pose edit(Pose p, Tool tool, int axis, double value) {
        if (!Double.isFinite(value) || axis < 0 || axis > 2) return p;
        Vector3d position = p.position(), rotation = p.rotation(), scale = p.scale();
        switch (tool) {
            case Move -> position.setComponent(axis, Math.clamp(value, -10, 10));
            case Rotate -> rotation.setComponent(axis, wrapDegrees(value));
            case Scale -> scale.setComponent(axis, Math.clamp(value, .05, 5));
        }
        Pose next = Pose.of(position, rotation, scale);
        if (tool == Tool.Move || Math.abs(scale.x * scale.y * scale.z) < 1e-9) return next;
        // Changing rotation/scale should pivot around the visible hand, even though the
        // persisted translation is applied last in the module's local coordinate system.
        Vector3d anchor = matrix(p).transformPosition(new Vector3d());
        Vector3d corrected = rotationScale(next).invert().transformPosition(anchor);
        return Pose.of(corrected, rotation, scale);
    }

    public static double wrapDegrees(double value) { return ((value + 180) % 360 + 360) % 360 - 180; }
    public static double axisDrag(double dx, double dy, double projectedX, double projectedY) {
        double length = projectedX * projectedX + projectedY * projectedY;
        return length < 1e-8 ? 0 : (dx * projectedX + dy * projectedY) / length;
    }

    /** Minimum-length local translation that follows a screen-plane mouse movement. */
    public static Vector3d planeDrag(double dx, double dy, double[][] axes) {
        double xx = 0, xy = 0, yy = 0;
        for (double[] a : axes) { xx += a[0] * a[0]; xy += a[0] * a[1]; yy += a[1] * a[1]; }
        double determinant = xx * yy - xy * xy;
        if (Math.abs(determinant) < 1e-8) return new Vector3d();
        double u = (yy * dx - xy * dy) / determinant, v = (xx * dy - xy * dx) / determinant;
        return new Vector3d(axes[0][0] * u + axes[0][1] * v, axes[1][0] * u + axes[1][1] * v, axes[2][0] * u + axes[2][1] * v);
    }

    public static double ringAngle(double dx, double dy, double ux, double uy, double vx, double vy) {
        double determinant = ux * vy - uy * vx;
        if (Math.abs(determinant) < 1e-6) return Double.NaN;
        return Math.atan2((ux * dy - uy * dx) / determinant, (dx * vy - dy * vx) / determinant);
    }

    public static double segmentDistance(double px, double py, double ax, double ay, double bx, double by) {
        double dx = bx - ax, dy = by - ay;
        double length = dx * dx + dy * dy;
        double t = length < 1e-8 ? 0 : Math.clamp(((px - ax) * dx + (py - ay) * dy) / length, 0, 1);
        return Math.hypot(px - ax - t * dx, py - ay - t * dy);
    }
    private HandLayout() {}
}
