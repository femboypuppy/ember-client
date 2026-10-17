package meteordevelopment.meteorclient.gui.screens;

import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WidgetScreen;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.systems.modules.render.HandView;
import meteordevelopment.meteorclient.utils.render.HandLayout;
import meteordevelopment.meteorclient.utils.render.HandLayout.Pose;
import meteordevelopment.meteorclient.utils.render.HandLayout.Snapshot;
import meteordevelopment.meteorclient.utils.render.HandLayout.Tool;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.CharInput;
import net.minecraft.client.input.KeyInput;
import net.minecraft.util.Arm;
import org.joml.Matrix4d;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.Utils.getWindowHeight;
import static meteordevelopment.meteorclient.utils.Utils.getWindowWidth;
import static org.lwjgl.glfw.GLFW.*;

/** Transactional layout editor. Its reference hands use the same R * S * T as Hand View. */
public final class HandEditorScreen extends WidgetScreen {
    private static final Color TEXT = new Color(235, 238, 248), DIM = new Color(152, 164, 187);
    private static final Color[] AXES = {new Color(245, 111, 128), new Color(110, 225, 163), new Color(113, 167, 255)};
    private final HandView module;
    private final Snapshot original;
    private final boolean originallyActive;
    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final List<Button> buttons = new ArrayList<>();
    private final List<Face> faces = new ArrayList<>();
    private final double[][] axisScreen = new double[3][2], ringU = new double[3][2], ringV = new double[3][2];
    private boolean main = true, finished, child, orbiting;
    private Tool tool = Tool.Move;
    private double mx, my, yaw = -.32, pitch = -.2, zoom = 1, panel, top, left, viewW, viewH, unit, cx, cy;
    private double lastX, lastY, startX, startY, angle, angleTotal, sidebarScroll, sidebarMax;
    private int dragging = -1, editing = -1;
    private String buffer = "", validation = "";
    private Snapshot dragBefore;
    private Pose dragPose;
    private record Button(double x, double y, double w, double h, Runnable action) {
        boolean contains(double px, double py) { return px >= x && py >= y && px < x + w && py < y + h; }
    }
    private record Point(double x, double y, double z) {}
    private record Face(Point[] points, double depth, Color color) {}

    public HandEditorScreen(GuiTheme theme, HandView module) {
        super(theme, "Hand layout editor"); this.module = module;
        original = module.snapshot(); originallyActive = module.isActive();
        if (!originallyActive && mc.world != null) module.toggle();
    }
    @Override public void initWidgets() { clear(); }
    @Override public boolean shouldPause() { return false; }
    private boolean held(int key) { return glfwGetKey(mc.getWindow().getHandle(), key) == GLFW_PRESS; }
    private boolean fine() { return held(GLFW_KEY_LEFT_SHIFT) || held(GLFW_KEY_RIGHT_SHIFT); }
    private boolean snap() { return held(GLFW_KEY_LEFT_CONTROL) || held(GLFW_KEY_RIGHT_CONTROL); }
    private boolean live() { return mc.world != null && held(GLFW_KEY_SPACE) && editing < 0; }
    private void remember(Snapshot state) { if (undo.size() == 32) undo.removeFirst(); undo.addLast(state); redo.clear(); }
    private void change(Runnable action) { Snapshot before = module.snapshot(); action.run(); if (!before.equals(module.snapshot())) remember(before); }
    private void history(boolean forward) {
        var source = forward ? redo : undo; var destination = forward ? undo : redo;
        if (!source.isEmpty()) { destination.addLast(module.snapshot()); module.restore(source.removeLast()); }
    }
    private void finish(boolean apply) {
        if (finished) return;
        cancelDrag(); editing = -1;
        if (!apply) module.restore(original);
        if (module.isActive() != originallyActive) module.toggle();
        finished = true;
        if (parent instanceof WidgetScreen screen) screen.reload();
        mc.setScreen(parent);
    }
    @Override public void close() { finish(false); }
    @Override public void removed() {
        super.removed();
        if (child) { child = false; return; }
        if (!finished) {
            cancelDrag(); module.restore(original);
            if (module.isActive() != originallyActive) module.toggle();
            finished = true;
        }
    }
    private void mirror() {
        if (!commitField()) return;
        child = true;
        mc.setScreen(new HandMirrorScreen(theme, module, () -> remember(module.snapshot())));
    }

    @Override protected void onRenderBefore(DrawContext graphics, float delta) {
        buttons.clear();
        if (live()) return;
        double w = getWindowWidth(), h = getWindowHeight();
        panel = Math.min(380, w * .37); left = panel + 32; top = 90;
        viewW = Math.max(80, w - left - 24); viewH = Math.max(80, h - 170);
        unit = Math.min(viewW / 3.8, viewH / 2.8) * zoom;
        GuiRenderer r = new GuiRenderer(); r.theme = theme; r.begin(graphics);
        r.roundedRect(12, 12, w - 24, h - 24, 18, EmberPalette.panel());
        label(r, "Hand layout", 32, 28, TEXT);
        label(r, "3D reference / live changes", left, 28, DIM);
        sidebar(r, h);
        r.scissorStart(left, top, viewW, viewH);
        viewport(r);
        r.scissorEnd();
        label(r, "Middle drag: orbit   Wheel: zoom   Shift: fine   Ctrl: snap", left, h - 69, DIM);
        label(r, "G / R / S: tool   Ctrl+Z: undo   Hold Space: view in game", left, h - 43, DIM);
        r.end();
    }
    private void sidebar(GuiRenderer r, double h) {
        double x = 30, width = panel - 20, gap = 8, row = 40;
        r.scissorStart(20, 74, panel, Math.max(1, h-164));
        double y = 80-sidebarScroll;
        button(r, "Main hand", x, y, (width-gap)/2, main, () -> select(true));
        button(r, "Off hand", x+(width+gap)/2, y, (width-gap)/2, !main, () -> select(false));
        y += row + 18;
        for (Tool t : Tool.values()) {
            double bx = x + t.ordinal() * (width+gap)/3;
            button(r, t.name(), bx, y, (width-2*gap)/3, tool == t, () -> { if (commitField()) tool = t; });
        }
        y += row + 18;
        Pose pose = module.pose(main);
        label(r, tool == Tool.Rotate ? "Rotation (degrees)" : tool == Tool.Scale ? "Scale" : "Local position (blocks)", x, y, DIM);
        y += 34;
        for (int axis = 0; axis < 3; axis++) {
            int a = axis; String value = editing == axis ? buffer + "|" : format(HandLayout.component(pose, tool, axis));
            label(r, "XYZ".substring(axis, axis+1), x, y+7, AXES[axis]);
            double fieldY = y;
            button(r, value, x+30, y, width-30, editing == axis, () -> {
                if (!commitField()) return;
                editing = a; buffer = format(HandLayout.component(module.pose(main), tool, a)); validation = "";
            });
            y = fieldY + 46;
        }
        label(r, validation.isEmpty() ? "Click a value to type. Enter confirms." : validation, x, y+3, DIM);
        y += 43;
        button(r, "Make symmitrical", x, y, width, false, this::mirror); y += row+8;
        button(r, "Reset selected hand", x, y, width, false, () -> { if (commitField()) change(() -> module.setPose(main, Pose.identity())); }); y += row+8;
        button(r, "Undo", x, y, (width-gap)/2, false, () -> { if (commitField()) history(false); });
        button(r, "Redo", x+(width+gap)/2, y, (width-gap)/2, false, () -> { if (commitField()) history(true); });
        sidebarMax = Math.max(0,y+row+24+sidebarScroll-(h-90));
        r.scissorEnd();
        y = h-65;
        button(r, "Cancel", x, y, (width-gap)/2, false, () -> finish(false));
        button(r, "Apply", x+(width+gap)/2, y, (width-gap)/2, true, () -> { if (commitField()) finish(true); });
    }
    private void select(boolean selected) { if (commitField()) main = selected; }
    private String format(double value) { return String.format(Locale.ROOT, "%.3f", value); }
    private void label(GuiRenderer r, String text, double x, double y, Color color) { r.text(text, x, y, color, false); }
    private void button(GuiRenderer r, String text, double x, double y, double w, boolean selected, Runnable action) {
        Button b = new Button(x, y, w, 34, action); buttons.add(b);
        r.roundedRect(x,y,w,34,8, selected ? EmberPalette.rowActive() : b.contains(mx,my) ? EmberPalette.controlHover() : EmberPalette.control());
        // Scissor keeps long typed values within their control.
        r.scissorStart(x+5,y,w-10,34);
        label(r,text,x+10,y+(34-theme.textHeight())/2,TEXT); r.scissorEnd();
    }

    private Matrix4d base(boolean mainHand) {
        boolean right = mc.player == null || mc.player.getMainArm() == Arm.RIGHT;
        double sign = mainHand == right ? 1 : -1;
        return new Matrix4d().translate(sign*.7, -.05, 0).rotateX(-.3).rotateY(-sign*.35);
    }
    private Point project(Vector3d v) {
        Vector3d p = new Matrix4d().rotateX(pitch).rotateY(yaw).transformPosition(new Vector3d(v));
        return new Point(left + viewW/2 + p.x*unit, top+viewH/2 - p.y*unit, p.z);
    }
    private double[] direction(Vector3d v) {
        Point p = project(v), zero = project(new Vector3d()); return new double[]{p.x-zero.x,p.y-zero.y};
    }
    private void viewport(GuiRenderer r) {
        Color grid = new Color(82,99,130,55);
        for (int i=-6;i<=6;i++) {
            line(r,project(new Vector3d(i*.4,-.85,-2.4)),project(new Vector3d(i*.4,-.85,2.4)),grid,1);
            line(r,project(new Vector3d(-2.4,-.85,i*.4)),project(new Vector3d(2.4,-.85,i*.4)),grid,1);
        }
        line(r,project(new Vector3d(0,-1,0)),project(new Vector3d(0,1.5,0)),new Color(168,182,220,70),1);
        faces.clear();
        hand(true); hand(false);
        faces.sort(Comparator.comparingDouble(Face::depth));
        for (Face f : faces) {
            Point[] p = f.points;
            r.triangle(p[0].x,p[0].y,p[1].x,p[1].y,p[2].x,p[2].y,f.color);
            r.triangle(p[0].x,p[0].y,p[2].x,p[2].y,p[3].x,p[3].y,f.color);
            for(int i=0;i<4;i++) line(r,p[i],p[(i+1)%4],new Color(200,217,255,100),1);
        }
        for (boolean m : new boolean[]{true,false}) {
            Point p = project(base(m).mul(HandLayout.matrix(module.pose(m))).transformPosition(new Vector3d(0,-.7,0)));
            label(r,m ? "Main" : "Off",p.x-18,p.y,DIM);
        }
        Pose pose = module.pose(main); Matrix4d transform = base(main).mul(HandLayout.matrix(pose));
        Vector3d center = transform.transformPosition(new Vector3d()); Point origin = project(center); cx=origin.x; cy=origin.y;
        for(int axis=0;axis<3;axis++) {
            Vector3d basis = new Vector3d().setComponent(axis,1);
            if (dragging < 0) axisScreen[axis] = direction(transform.transformDirection(new Vector3d(basis)));
            if(tool == Tool.Rotate) {
                Matrix4d frame=base(main);
                if(axis>0) frame.rotateX(Math.toRadians(pose.rx()));
                if(axis>1) frame.rotateY(Math.toRadians(pose.ry()));
                ringU[axis]=direction(frame.transformDirection(new Vector3d().setComponent((axis+1)%3,.48)));
                ringV[axis]=direction(frame.transformDirection(new Vector3d().setComponent((axis+2)%3,.48)));
                for(int i=0;i<64;i++) line(r,ringPoint(axis,i*Math.PI/32),ringPoint(axis,(i+1)*Math.PI/32),AXES[axis],2);
            } else {
                double[] d=axisScreen[axis]; double len=Math.hypot(d[0],d[1]); if(len<1e-4) continue;
                double ex=cx+d[0]/len*85, ey=cy+d[1]/len*85;
                line(r,origin,new Point(ex,ey,0),AXES[axis],3);
                r.roundedRect(ex-6,ey-6,12,12,tool==Tool.Scale ? 2 : 6,AXES[axis]);
                label(r,"XYZ".substring(axis,axis+1),ex+9,ey-10,AXES[axis]);
            }
        }
        if(tool==Tool.Move) r.roundedRect(cx-6,cy-6,12,12,4,TEXT);
    }
    private Point ringPoint(int axis,double angle) {
        return new Point(cx+ringU[axis][0]*Math.cos(angle)+ringV[axis][0]*Math.sin(angle),cy+ringU[axis][1]*Math.cos(angle)+ringV[axis][1]*Math.sin(angle),0);
    }
    private void hand(boolean selectedMain) {
        Matrix4d matrix=base(selectedMain).mul(HandLayout.matrix(module.pose(selectedMain)));
        Point[] p=new Point[8];
        for(int i=0;i<8;i++) p[i]=project(matrix.transformPosition(new Vector3d((i&1)==0?-.17:.17,(i&2)==0?-.55:.4,(i&4)==0?-.15:.15)));
        int[][] indices={{0,1,3,2},{4,6,7,5},{0,4,5,1},{2,3,7,6},{0,2,6,4},{1,5,7,3}};
        for(int i=0;i<indices.length;i++) {
            Point[] points=new Point[4]; double depth=0;
            for(int j=0;j<4;j++) { points[j]=p[indices[i][j]]; depth+=points[j].z; }
            int shade=18*i; Color accent=EmberPalette.accent();
            Color c=selectedMain==main ? new Color(accent.r/2+shade/2,accent.g/2+shade/2,accent.b/2+shade/2,255) : new Color(51+shade/3,65+shade/3,89+shade/3,255);
            faces.add(new Face(points,depth/4,c));
        }
    }
    private void line(GuiRenderer r,Point a,Point b,Color c,double width) {
        double dx=b.x-a.x,dy=b.y-a.y,len=Math.hypot(dx,dy); if(len<1e-6)return;
        double nx=-dy/len*width/2,ny=dx/len*width/2;
        r.triangle(a.x+nx,a.y+ny,b.x+nx,b.y+ny,b.x-nx,b.y-ny,c);
        r.triangle(a.x+nx,a.y+ny,b.x-nx,b.y-ny,a.x-nx,a.y-ny,c);
    }
    private int hitAxis() {
        if(tool==Tool.Move && Math.hypot(mx-cx,my-cy)<12)return 3;
        int best=-1; double distance=11;
        for(int a=0;a<3;a++) {
            double d=Double.POSITIVE_INFINITY;
            if(tool==Tool.Rotate) {
                if(Math.abs(ringU[a][0]*ringV[a][1]-ringU[a][1]*ringV[a][0])<1e-6)continue;
                for(int i=0;i<64;i++) { Point p=ringPoint(a,i*Math.PI/32),q=ringPoint(a,(i+1)*Math.PI/32); d=Math.min(d,HandLayout.segmentDistance(mx,my,p.x,p.y,q.x,q.y)); }
            } else {
                double[] v=axisScreen[a]; double len=Math.hypot(v[0],v[1]);
                if(len>1e-4)d=HandLayout.segmentDistance(mx,my,cx+v[0]/len*14,cy+v[1]/len*14,cx+v[0]/len*85,cy+v[1]/len*85);
            }
            if(d<distance) { best=a; distance=d; }
        }
        return best;
    }
    @Override public boolean mouseClicked(Click click,boolean doubled) {
        mx=click.x()*mc.getWindow().getScaleFactor(); my=click.y()*mc.getWindow().getScaleFactor();
        if(click.button()==1 && dragging>=0) { cancelDrag();return true; }
        if(dragging>=0)return true;
        if(live())return true;
        if(click.button()==0) {
            for(int i=buttons.size()-1;i>=0;i--) {
                Button b=buttons.get(i);
                boolean visible=my>=getWindowHeight()-75 || (my>=74 && my<getWindowHeight()-90);
                if(visible&&b.contains(mx,my)){b.action.run();return true;}
            }
            if(!commitField())return true;
        }
        if(mx<left||my<top||mx>left+viewW||my>top+viewH)return true;
        if(click.button()==2) { orbiting=true;lastX=mx;lastY=my;return true; }
        if(click.button()!=0)return true;
        dragging=hitAxis();
        if(dragging>=0) {
            dragBefore=module.snapshot();dragPose=module.pose(main);startX=mx;startY=my;angleTotal=0;
            if(tool==Tool.Rotate) angle=HandLayout.ringAngle(mx-cx,my-cy,ringU[dragging][0],ringU[dragging][1],ringV[dragging][0],ringV[dragging][1]);
        }
        return true;
    }
    @Override public void mouseMoved(double x,double y) {
        mx=x*mc.getWindow().getScaleFactor();my=y*mc.getWindow().getScaleFactor();
        if(orbiting){yaw+=(mx-lastX)*.008;pitch=Math.clamp(pitch+(my-lastY)*.008,-1.35,1.35);lastX=mx;lastY=my;}
        if(dragging<0)return;
        double dx=mx-startX,dy=my-startY,factor=fine()?.15:1;
        Pose next=dragPose;
        if(dragging==3) {
            Vector3d delta=HandLayout.planeDrag(dx*factor,dy*factor,axisScreen);
            for(int a=0;a<3;a++)next=HandLayout.edit(next,Tool.Move,a,quantize(dragPose.position().get(a)+delta.get(a),.1));
        } else {
            double value=HandLayout.component(dragPose,tool,dragging);
            if(tool==Tool.Rotate) {
                double nextAngle=HandLayout.ringAngle(mx-cx,my-cy,ringU[dragging][0],ringU[dragging][1],ringV[dragging][0],ringV[dragging][1]);
                if(!Double.isFinite(nextAngle)||!Double.isFinite(angle))return;
                angleTotal+=HandLayout.wrapDegrees(Math.toDegrees(nextAngle-angle));angle=nextAngle;
                value+=angleTotal*factor;
            } else {
                double[] v=axisScreen[dragging];
                value+=HandLayout.axisDrag(dx,dy,v[0],v[1])*factor*(tool==Tool.Scale?.7:1);
            }
            next=HandLayout.edit(dragPose,tool,dragging,quantize(value,tool==Tool.Rotate?15:.1));
        }
        module.setPose(main,next);
    }
    private double quantize(double v,double step){return snap()?Math.rint(v/step)*step:v;}
    private void cancelDrag(){if(dragging>=0&&dragBefore!=null)module.restore(dragBefore);dragging=-1;orbiting=false;}
    @Override public boolean mouseReleased(Click click) {
        if(dragging>=0&&dragBefore!=null&&!dragBefore.equals(module.snapshot()))remember(dragBefore);
        dragging=-1;orbiting=false;return true;
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(dragging<0) {
            if(x*mc.getWindow().getScaleFactor()<left) sidebarScroll=Math.clamp(sidebarScroll-vertical*36,0,sidebarMax);
            else zoom=Math.clamp(zoom*Math.pow(1.1,vertical),.35,3);
        }
        return true;
    }
    private boolean commitField() {
        if(editing<0)return true;
        try {
            double value=Double.parseDouble(buffer);
            if(!Double.isFinite(value))throw new NumberFormatException();
            int axis=editing;change(()->module.setPose(main,HandLayout.edit(module.pose(main),tool,axis,value)));
            editing=-1;validation="";return true;
        } catch(NumberFormatException ignored){validation="Enter a finite number.";return false;}
    }
    @Override public boolean keyPressed(KeyInput input) {
        int key=input.key();
        if(editing>=0) {
            if(key==GLFW_KEY_ESCAPE){editing=-1;validation="";return true;}
            if(key==GLFW_KEY_ENTER||key==GLFW_KEY_KP_ENTER){commitField();return true;}
            if(key==GLFW_KEY_BACKSPACE){if(!buffer.isEmpty())buffer=buffer.substring(0,buffer.length()-1);return true;}
            if(key==GLFW_KEY_A&&(input.modifiers()&GLFW_MOD_CONTROL)!=0){buffer="";return true;}
            return true;
        }
        if(key==GLFW_KEY_ESCAPE){if(dragging>=0)cancelDrag();else finish(false);return true;}
        if(dragging>=0)return true;
        if((input.modifiers()&GLFW_MOD_CONTROL)!=0 && key==GLFW_KEY_Z){history((input.modifiers()&GLFW_MOD_SHIFT)!=0);return true;}
        if(key==GLFW_KEY_G)tool=Tool.Move;
        if(key==GLFW_KEY_R)tool=Tool.Rotate;
        if(key==GLFW_KEY_S)tool=Tool.Scale;
        return true;
    }
    @Override public boolean charTyped(CharInput input) {
        if(editing>=0&&buffer.length()<32&&"0123456789.-+eE".indexOf(input.codepoint())>=0)buffer+=Character.toString(input.codepoint());
        return true;
    }
}
