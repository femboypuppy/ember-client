package meteordevelopment.meteorclient.utils.render;

import org.joml.Matrix4d;
import org.joml.Vector3d;
import java.util.Random;

/** Exercise mirroring and cursor math against the real persisted hand transformation. */
final class HandLayoutChecks {
    static void run() {
        Random random = new Random(4572);
        Matrix4d reflection = new Matrix4d().scaling(-1, 1, 1);
        for (int i = 0; i < 1000; i++) {
            HandLayout.Pose pose = new HandLayout.Pose(random.nextDouble()*4-2, random.nextDouble()*4-2,
                random.nextDouble()*4-2, random.nextDouble()*360-180, random.nextDouble()*360-180,
                random.nextDouble()*360-180, .1+random.nextDouble()*3, .1+random.nextDouble()*3, .1+random.nextDouble()*3);
            require(HandLayout.mirror(HandLayout.mirror(pose)).equals(pose), "Mirroring twice restores every pose component");
            Matrix4d expected = new Matrix4d(reflection).mul(HandLayout.matrix(pose)).mul(reflection);
            require(expected.equals(HandLayout.matrix(HandLayout.mirror(pose)), 1e-9), "Mirror must reflect the whole R * S * T transform, including nonuniform scale");
            for (HandLayout.Tool tool : new HandLayout.Tool[]{HandLayout.Tool.Rotate, HandLayout.Tool.Scale}) {
                for (int axis = 0; axis < 3; axis++) {
                    var next = HandLayout.edit(pose, tool, axis, tool == HandLayout.Tool.Rotate ? 135 : 1.4);
                    Vector3d before = HandLayout.matrix(pose).transformPosition(new Vector3d());
                    Vector3d after = HandLayout.matrix(next).transformPosition(new Vector3d());
                    require(before.distance(after) < 1e-8, "Rotation and scale must pivot around the visible hand");
                    require(HandLayout.edit(pose,tool,axis,Double.NaN).equals(pose), "Invalid numeric input cannot change the pose");
                }
            }
        }
        double[][] projected = {{120,30},{-20,-140},{70,-50}};
        Vector3d move = HandLayout.planeDrag(46,-73,projected);
        require(Math.abs(move.x*120-move.y*20+move.z*70-46)<1e-9, "Free dragging follows horizontal cursor movement");
        require(Math.abs(move.x*30-move.y*140-move.z*50+73)<1e-9, "Free dragging follows vertical cursor movement");
        require(HandLayout.planeDrag(1,2,new double[][]{{0,0},{0,0},{0,0}}).equals(new Vector3d()), "Degenerate camera projections stay finite");
        require(HandLayout.axisDrag(30,40,3,4)==10, "Constrained dragging uses the projected local axis");
        require(HandLayout.axisDrag(30,40,0,0)==0, "End-on axes cannot cause huge jumps");
        for(double angle : new double[]{-3,-1,0,.4,2.8}) {
            double x=110*Math.cos(angle)-30*Math.sin(angle), y=40*Math.cos(angle)+70*Math.sin(angle);
            require(Math.abs(HandLayout.ringAngle(x,y,110,40,-30,70)-angle)<1e-9, "Rotation follows the projected ellipse, not a screen-space circle");
        }
        require(Double.isNaN(HandLayout.ringAngle(1,1,1,0,2,0)), "Edge-on rings are not draggable");
        require(HandLayout.wrapDegrees(359)==-1 && HandLayout.wrapDegrees(-359)==1, "Crossing the rotation seam cannot jump 360 degrees");
        require(HandLayout.edit(HandLayout.Pose.identity(),HandLayout.Tool.Scale,0,-10).sx()==.05, "Editor scaling cannot invert or collapse a hand");
        System.out.println("Hand layout checks passed: 1,000 mirrored transforms, fixed pivots, cursor projection, rotation seams and invalid input.");
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
