package meteordevelopment.meteorclient.utils.render;

/** Depth-tested weather meshes; coordinates follow CelestialRenderer's local Z convention. */
public final class WeatherGeometry {
    public static void build(CelestialMesh mesh, WeatherSimulation simulation, double cx, double cy, double cz,
                             double delta, double radius, int accent, boolean tint) {
        var kind=simulation.kind();double time=simulation.time();
        for(var p:simulation.particles()) {
            if(!p.active)continue;
            double x=p.px+(p.x-p.px)*delta-cx,y=p.py+(p.y-p.py)*delta-cy,z=cz-p.pz-(p.z-p.pz)*delta;
            double distance=Math.sqrt(x*x+y*y+z*z);
            double fade=Math.clamp((distance-.5)/1.3,0,1)*Math.clamp(1-distance/(radius+8),0,1)*Math.clamp(p.age/.25,0,1);
            if(fade<.005)continue;
            int natural=switch(kind){case Rain->0xB1C9E0;case Snow->0xF1F5FF;case Leaves->p.seed<.35?0xD58E38:p.seed<.7?0xAB542B:0xE4B553;case Ash->0xAAA5A2;case Petals->0xF3ABC9;};
            int rgb=tint?blend(natural,accent,.5):natural;
            if(p.landed) {
                double age=p.impactAge/WeatherSimulation.impactLifetime(kind,p.water);
                if(kind==WeatherSimulation.Kind.Rain) {
                    if(p.water)ring(mesh,x,y,z,.035+age*.22,fade*(1-age)*.6,rgb);
                    else for(int i=0;i<4;i++) {
                        double a=p.seed*6.28+i*Math.PI/2,r=.025+age*.11;
                        streak(mesh,x+Math.cos(a)*r,y+Math.sin(age*Math.PI)*.14,z+Math.sin(a)*r,
                            x+Math.cos(a)*r*.6,y+.015,z+Math.sin(a)*r*.6,.012,rgba(rgb,fade*(1-age)*.65),rgba(rgb,0));
                    }
                } else {
                    double size=kind==WeatherSimulation.Kind.Snow?.075:.115;
                    int c=rgba(rgb,fade*(1-age)*.65);
                    mesh.quad(x-size,y,z-size,x+size,y,z-size,x+size,y,z+size,x-size,y,z+size,c,c,c,c);
                }
                continue;
            }
            if(kind==WeatherSimulation.Kind.Rain) {
                double length=.035+p.seed*.025;
                streak(mesh,x,y,z,x-p.vx*length,y-p.vy*length,z+p.vz*length,.01+p.seed*.004,
                    rgba(rgb,fade*.58),rgba(rgb,fade*.1));
                continue;
            }
            double horizontal=Math.hypot(x,z),ux=horizontal<1e-5?1:z/horizontal,uz=horizontal<1e-5?0:-x/horizontal;
            double vx=y/distance*uz,vy=(z*ux-x*uz)/distance,vz=-y/distance*ux;
            double turn=time*(kind==WeatherSimulation.Kind.Leaves?2.2:1.2)+p.seed*20;
            double size=switch(kind){case Snow->.045+p.seed*.045;case Leaves->.11+p.seed*.05;case Ash->.025+p.seed*.02;default->.075+p.seed*.035;};
            int sides=kind==WeatherSimulation.Kind.Snow?12:6;
            for(int i=0;i<sides;i++) {
                double a=turn+i*Math.PI*2/sides,b=turn+(i+1)*Math.PI*2/sides;
                double ra=kind==WeatherSimulation.Kind.Snow&&i%2==1?size*.25:size;
                double rb=kind==WeatherSimulation.Kind.Snow&&i%2==0?size*.25:size;
                double ax=Math.cos(a)*ra,ay=Math.sin(a)*ra,bx=Math.cos(b)*rb,by=Math.sin(b)*rb;
                double fold=kind==WeatherSimulation.Kind.Leaves||kind==WeatherSimulation.Kind.Petals?Math.cos(turn*.7)*.65:1;
                int center=rgba(rgb,fade*.9),edge=rgba(rgb,fade*(kind==WeatherSimulation.Kind.Ash?.36:.7));
                mesh.tri(x,y,z,x+ux*ax+vx*ay*fold,y+vy*ay*fold,z+uz*ax+vz*ay*fold,
                    x+ux*bx+vx*by*fold,y+vy*by*fold,z+uz*bx+vz*by*fold,center,edge,edge);
            }
            if(kind==WeatherSimulation.Kind.Snow) {
                int center=rgba(rgb,fade*.14),clear=rgba(rgb,0);
                for(int i=0;i<6;i++) {
                    double a=i*Math.PI/3,b=(i+1)*Math.PI/3;
                    double ax=Math.cos(a)*size*2,ay=Math.sin(a)*size*2,bx=Math.cos(b)*size*2,by=Math.sin(b)*size*2;
                    mesh.tri(x,y,z,x+ux*ax+vx*ay,y+vy*ay,z+uz*ax+vz*ay,
                        x+ux*bx+vx*by,y+vy*by,z+uz*bx+vz*by,center,clear,clear);
                }
            }
        }
    }
    private static void streak(CelestialMesh mesh,double ax,double ay,double az,double bx,double by,double bz,double width,int a,int b) {
        double dx=bx-ax,dy=by-ay,dz=bz-az,ux=ay*dz-az*dy,uy=az*dx-ax*dz,uz=ax*dy-ay*dx;
        double length=Math.sqrt(ux*ux+uy*uy+uz*uz);
        if(length<1e-8)return;
        width/=length;ux*=width;uy*=width;uz*=width;
        mesh.quad(ax-ux,ay-uy,az-uz,ax+ux,ay+uy,az+uz,bx+ux,by+uy,bz+uz,bx-ux,by-uy,bz-uz,a,a,b,b);
    }
    private static void ring(CelestialMesh mesh,double x,double y,double z,double radius,double opacity,int rgb) {
        int c=rgba(rgb,opacity),clear=rgba(rgb,0);double inner=Math.max(0,radius-.012);
        for(int i=0;i<16;i++) {
            double a=i*Math.PI/8,b=(i+1)*Math.PI/8;
            mesh.quad(x+Math.cos(a)*inner,y,z+Math.sin(a)*inner,x+Math.cos(a)*radius,y,z+Math.sin(a)*radius,
                x+Math.cos(b)*radius,y,z+Math.sin(b)*radius,x+Math.cos(b)*inner,y,z+Math.sin(b)*inner,clear,c,c,clear);
        }
    }
    private static int rgba(int rgb,double opacity){return ((int)(Math.clamp(opacity,0,1)*255)<<24)|(rgb&0xFFFFFF);}
    private static int blend(int a,int b,double mix) {
        int r=(int)((a>>16&255)*(1-mix)+(b>>16&255)*mix),g=(int)((a>>8&255)*(1-mix)+(b>>8&255)*mix),bl=(int)((a&255)*(1-mix)+(b&255)*mix);
        return r<<16|g<<8|bl;
    }
    private WeatherGeometry(){}
}
