package meteordevelopment.meteorclient.utils.render;

/** Production simulation and geometry checks, with synthetic roof/water/slab columns. */
final class WeatherChecks {
    static void run() {
        var slab=new WeatherSurface.Bounds(0,0,0,1,.5,1);
        var upper=new WeatherSurface.Bounds(.5,.5,0,1,1,1);
        var full=new WeatherSurface.Bounds(0,0,0,1,1,1);
        var column=new WeatherSurface.Column(10,new WeatherSurface.Bounds[]{slab,upper},new WeatherSurface.Bounds[]{full},Double.NEGATIVE_INFINITY,false);
        require(column.at(.25,.5).height()==10.5&&column.at(.75,.5).height()==11,"Rain must use the actual stair step under each particle");
        var logged=new WeatherSurface.Column(10,new WeatherSurface.Bounds[]{slab,upper},new WeatherSurface.Bounds[]{full},10.9,true);
        require(logged.at(.25,.5).water()&&!logged.at(.75,.5).water(),"Waterlogged stairs choose water or solid impacts at the actual hit point");
        var mesh=new CelestialMesh(36000);
        int waterHits=0,solidHits=0;
        for(var kind:WeatherSimulation.Kind.values()) {
            var simulation=new WeatherSimulation();
            for(int tick=0;tick<900;tick++) {
                simulation.tick(.05,0,2,0,kind,1,20,.6,35,1,true,(x,z)->new WeatherSimulation.Surface(x<0?.5:0,x>=0));
                require(simulation.count()<=WeatherSimulation.CAPACITY,"Particles must remain in the fixed pool");
                for(var p:simulation.particles())if(p.active) {
                    require(Double.isFinite(p.x+p.y+p.z+p.vx+p.vy+p.vz),"Weather physics must remain finite");
                    require(p.y>=(p.x<0?.5:0),"Weather cannot fall through slabs or water");
                    if(p.landed){if(p.water)waterHits++;else solidHits++;}
                }
                if(tick%19==0) {
                    mesh.clear();WeatherGeometry.build(mesh,simulation,0,2,0,.5,20,0xA78BE0,false);
                    valid(mesh);
                }
            }
            require(simulation.count()>0,"Every weather style must produce visible particles");
            mesh.clear();WeatherGeometry.build(mesh,simulation,0,2,0,1,20,0xFF2040,true);valid(mesh);
            simulation.tick(.05,0,2,0,kind,1,20,0,0,1,true,(x,z)->null);
            require(simulation.count()==0,"Unloaded columns must remove both falling and settled particles");
            for(int i=0;i<50;i++)simulation.tick(.05,0,2,0,kind,1,20,0,0,1,true,(x,z)->new WeatherSimulation.Surface(100,false));
            require(simulation.count()==0,"A cave ceiling must prevent local weather spawning below it");
            for(int i=0;i<100;i++)simulation.tick(.05,0,2,0,kind,1,20,.5,0,1,true,(x,z)->new WeatherSimulation.Surface(5,false));
            for(var p:simulation.particles())if(p.active)require(p.y>=5,"Particles above a roof cannot pass through it");
            simulation.tick(.05,10000,90,-10000,kind,1,20,0,0,1,true,(x,z)->new WeatherSimulation.Surface(80,false));
            for(var p:simulation.particles())if(p.active)require(Math.abs(p.x-10000)<=20&&Math.abs(p.z+10000)<=20,"Teleport must clear the old weather volume");
            simulation.tick(.05,10000,90,-10000,kind,0,20,0,0,1,true,(x,z)->new WeatherSimulation.Surface(80,false));
            require(simulation.count()==0,"Zero density removes custom particles immediately");
        }
        require(waterHits>0&&solidHits>0,"Both water ripples and solid-ground impacts must occur");
        var east=new WeatherSimulation();var west=new WeatherSimulation();
        for(int i=0;i<120;i++) {
            east.tick(.05,0,10,0,WeatherSimulation.Kind.Rain,1,40,1,0,1,false,(x,z)->new WeatherSimulation.Surface(0,false));
            west.tick(.05,0,10,0,WeatherSimulation.Kind.Rain,1,40,1,180,1,false,(x,z)->new WeatherSimulation.Surface(0,false));
        }
        double ex=0,wx=0;
        for(var p:east.particles())if(p.active)ex+=p.vx;
        for(var p:west.particles())if(p.active)wx+=p.vx;
        require(ex>10&&wx<-10,"Changing wind heading must reverse rain movement");
        var priority=new WeatherSimulation();
        for(int i=0;i<WeatherSimulation.CAPACITY;i++) {
            var p=priority.particles()[i];p.active=true;p.x=p.px=i*.01;p.y=p.py=10;p.life=100;
        }
        var refreshed=new java.util.HashSet<Integer>();
        for(int tick=0;tick<20;tick++) {
            int[] queries={0};
            priority.tick(0,0,2,0,WeatherSimulation.Kind.Rain,1,20,0,0,1,true,(x,z)->{
                if(queries[0]++<48)refreshed.add((int)Math.round(x*100));
                return new WeatherSimulation.Surface(0,false);
            });
        }
        require(refreshed.size()==WeatherSimulation.CAPACITY,"Every particle must eventually receive fresh collision data under a limited sampler budget");
        for(double density:new double[]{0,.1,.55,1}) {
            int count=(int)(density*1200);var graph=CelestialWorldGeometry.constellationEdges(density);
            require(graph==CelestialWorldGeometry.constellationEdges(density),"Constellation graph must be reused while density stays fixed");
            require(graph.length<=32,"Constellation line count stays bounded");
            for(var edge:graph)require(edge[0]>=0&&edge[0]<count&&edge[1]>=0&&edge[1]<count&&edge[0]!=edge[1],"Lines must connect existing distinct stars");
            mesh.clear();CelestialWorldGeometry.constellations(mesh,12,density,1,0xA78BE0,8);valid(mesh);
            int[] colors=java.util.Arrays.copyOf(mesh.colors,mesh.size);
            mesh.clear();CelestialWorldGeometry.constellations(mesh,25,density,1,0xA78BE0,8);valid(mesh);
            if(density>0)require(!java.util.Arrays.equals(colors,java.util.Arrays.copyOf(mesh.colors,mesh.size)),"Constellation lines must slowly fade");
        }
        var fog=AtmosphereFog.range(48,224,.3);
        require(fog.start()==48&&fog.end()==224&&Math.abs(fog.opacity()-.3)<1e-6,"Fog uses explicit distances and an independent opacity cap");
        require(AtmosphereFog.range(300,100,.3).end()>300,"Crossed fog sliders cannot create an inverted fog range");
        require(AtmosphereFog.range(48,224,0).opacity()==0,"Zero density restores clear custom haze");
        System.out.println("Weather checks passed: all five styles, roof/cave shelter, water/slab impacts, wind reversal, teleports, unloaded support, bounded meshes, stable constellations and explicit fog ranges.");
    }
    private static void valid(CelestialMesh mesh) {
        require(mesh.size<=mesh.colors.length&&mesh.size%3==0,"Weather must submit complete triangles within its mesh budget");
        for(int i=0;i<mesh.size*3;i++)require(Float.isFinite(mesh.positions[i]),"All weather mesh vertices must remain finite");
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
