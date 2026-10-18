package meteordevelopment.meteorclient.utils.render;

/** Per-point surface height, so rain hits the lower half of a stair instead of hovering above it. */
public final class WeatherSurface {
    public record Bounds(double x0,double y0,double z0,double x1,double y1,double z1) {}
    public record Column(int y,Bounds[] top,Bounds[] below,double fluidHeight,boolean water) {
        public WeatherSimulation.Surface at(double x,double z) {
            double height=Math.max(WeatherSurface.top(top,x,z,y),WeatherSurface.top(below,x,z,y-1));
            boolean hitsWater=water&&fluidHeight>height+1e-5;
            height=Math.max(height,fluidHeight);
            return Double.isFinite(height)?new WeatherSimulation.Surface(height,hitsWater):null;
        }
    }
    private static double top(Bounds[] boxes,double x,double z,int y) {
        double height=Double.NEGATIVE_INFINITY;
        for(var b:boxes)if(x>=b.x0&&x<=b.x1&&z>=b.z0&&z<=b.z1)height=Math.max(height,y+b.y1);
        return height;
    }
    private WeatherSurface(){}
}
