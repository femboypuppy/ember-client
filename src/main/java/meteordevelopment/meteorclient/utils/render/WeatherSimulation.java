package meteordevelopment.meteorclient.utils.render;

import java.util.Random;

/** Bounded local weather physics. The sampler may return null for unloaded or deferred columns. */
public final class WeatherSimulation {
    public enum Kind { Rain, Snow, Leaves, Ash, Petals }
    public record Surface(double height, boolean water) {}
    public interface Sampler { Surface sample(double x, double z); }
    public static final int CAPACITY = 512;
    public static final class Particle {
        public boolean active, landed, water;
        public double x,y,z,px,py,pz,vx,vy,vz,age,life,impactAge,seed;
    }
    private final Particle[] particles = new Particle[CAPACITY];
    private final Random random = new Random(0xE4BE27);
    private Kind kind = Kind.Rain;
    private double carry, time, lastX, lastY, lastZ;
    private boolean positioned;
    private int cursor;
    public WeatherSimulation() { for (int i=0;i<CAPACITY;i++) particles[i]=new Particle(); }
    public Particle[] particles() { return particles; }
    public Kind kind() { return kind; }
    public double time() { return time; }
    public int count() { int count=0;for(Particle p:particles)if(p.active)count++;return count; }
    public void clear() { for(Particle p:particles)p.active=false;carry=0;positioned=false; }

    public void disturb(double x,double y,double z,double dx,double dz,double amount) {
        if(kind!=Kind.Leaves&&kind!=Kind.Petals&&kind!=Kind.Snow)return;
        for(var p:particles) {
            if(!p.active||p.landed)continue;
            double rx=p.x-x,rz=p.z-z,d=Math.hypot(rx,rz);
            if(d<.05||d>1.8||p.y<y-.2||p.y>y+2)continue;
            double push=(1-d/1.8)*Math.clamp(amount,0,1)*Math.min(1,Math.hypot(dx,dz)*5);
            p.vx=Math.clamp(p.vx+rx/d*push*1.6+dx*push*3,-6,6);
            p.vz=Math.clamp(p.vz+rz/d*push*1.6+dz*push*3,-6,6);
        }
    }

    public void tick(double dt, double cx, double cy, double cz, Kind requested, double density, double radius,
                     double wind, double heading, double speed, boolean impacts, Sampler sampler) {
        if (requested!=kind || positioned && Math.sqrt(Math.pow(cx-lastX,2)+Math.pow(cy-lastY,2)+Math.pow(cz-lastZ,2))>radius*3) clear();
        kind=requested;lastX=cx;lastY=cy;lastZ=cz;positioned=true;
        if(density<=0){clear();return;}
        dt=Math.clamp(dt,0,.1);time+=dt;
        int target=(int)Math.round(CAPACITY*Math.clamp(density,0,1));
        double angle=Math.toRadians(heading),gust=wind*4*(1+.3*Math.sin(time*.53)+.15*Math.sin(time*1.31));
        double wx=Math.cos(angle)*gust,wz=Math.sin(angle)*gust;
        int alive=count(),landed=0;
        for(Particle p:particles)if(p.active&&p.landed)landed++;
        // Rotate sampling priority so late pool entries cannot retain stale roof data
        // indefinitely when the world's surface refresh budget is exhausted.
        for(int slot=0;slot<CAPACITY;slot++) {
            Particle p=particles[(slot+cursor)%CAPACITY];
            if(!p.active)continue;
            if(alive>target){p.active=false;alive--;continue;}
            p.px=p.x;p.py=p.y;p.pz=p.z;p.age+=dt;
            if(p.landed) {
                Surface support=sampler.sample(p.x,p.z);
                if(support==null||Math.abs(support.height-(p.y-.025))>.2){p.active=false;alive--;continue;}
                p.impactAge+=dt;
                if(!impacts||p.impactAge>impactLifetime(kind,p.water)){p.active=false;alive--;}
                continue;
            }
            if(p.age>p.life||Math.hypot(p.x-cx,p.z-cz)>radius+4||p.y<cy-28||p.y>cy+22){p.active=false;alive--;continue;}
            double flutter=kind==Kind.Rain?0:Math.sin(time*1.9+p.seed*30)*.45;
            p.vx+=(wx+flutter-p.vx)*Math.min(1,dt*3);
            p.vz+=(wz+Math.cos(time*1.6+p.seed*20)*Math.abs(flutter)-p.vz)*Math.min(1,dt*3);
            double nx=p.x+p.vx*dt*speed,nz=p.z+p.vz*dt*speed;
            Surface floor=sampler.sample(nx,nz);
            if(floor==null||p.y<floor.height-.02){p.active=false;alive--;continue;}
            double ny=p.y+p.vy*dt*speed;
            if(ny<=floor.height) {
                if(!impacts||landed>=96){p.active=false;alive--;continue;}
                p.landed=true;p.water=floor.water;p.impactAge=0;p.x=nx;p.z=nz;p.y=floor.height+.025;
                p.px=p.x;p.py=p.y;p.pz=p.z;landed++;
            } else {p.x=nx;p.y=ny;p.z=nz;}
        }
        cursor=(cursor+37)%CAPACITY;
        double turnover=switch(kind){case Rain->.95;case Snow->.17;case Leaves->.15;case Ash->.12;case Petals->.18;};
        carry=Math.min(20,carry+target*turnover*dt*speed);
        int births=Math.min(20,(int)carry);carry-=births;
        for(Particle p:particles) {
            if(births==0||alive>=target)break;
            if(p.active)continue;
            births--;
            double a=random.nextDouble()*Math.PI*2,r=Math.sqrt(random.nextDouble())*radius;
            double x=cx+Math.cos(a)*r,z=cz+Math.sin(a)*r;
            double y=cy+(kind==Kind.Rain?6:-2)+random.nextDouble()*(kind==Kind.Rain?9:16);
            Surface floor=sampler.sample(x,z);
            if(floor==null||y<=floor.height+.3)continue;
            p.active=true;p.landed=false;p.water=false;p.x=p.px=x;p.y=p.py=y;p.z=p.pz=z;
            p.seed=random.nextDouble();p.age=0;p.impactAge=0;p.vx=wx;p.vz=wz;
            p.vy=switch(kind){case Rain->-14-p.seed*5;case Snow->-1.4-p.seed;case Leaves->-1-p.seed*.7;case Ash->-.35-p.seed*.3;case Petals->-1.1-p.seed*.65;};
            p.life=kind==Kind.Rain?3:25;alive++;
        }
    }
    public static double impactLifetime(Kind kind,boolean water) {
        if(water&&kind!=Kind.Rain)return .4;
        return switch(kind){case Rain->water?.55:.3;case Snow->1.2;case Leaves->2;case Ash->.4;case Petals->1.6;};
    }
}
