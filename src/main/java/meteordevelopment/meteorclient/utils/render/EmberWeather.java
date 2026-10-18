package meteordevelopment.meteorclient.utils.render;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.utils.PostInit;
import meteordevelopment.meteorclient.utils.render.color.EmberPalette;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/** Local precipitation; loaded columns only, with a strict surface-query and particle budget. */
public final class EmberWeather {
    private static final WeatherSimulation SIMULATION=new WeatherSimulation();
    private static final CelestialMesh MESH=new CelestialMesh(36000);
    private record Sample(WeatherSurface.Column column,int tick) {}
    private static final Long2ObjectOpenHashMap<Sample> SURFACES=new Long2ObjectOpenHashMap<>();
    private static final BlockPos.Mutable POS=new BlockPos.Mutable();
    private static Object world;
    private static int tick,queries;
    private static double lastX,lastZ;
    private static Renderer3D renderer;
    private EmberWeather(){}
    @PostInit public static void init(){MeteorClient.EVENT_BUS.subscribe(EmberWeather.class);}
    public static boolean active(){var c=Config.get();return c!=null&&c.customWeather.get()&&EmberWorldAtmosphere.openSky();}

    public static void disturb(double x,double y,double z,double dx,double dz,double strength){if(active())SIMULATION.disturb(x,y,z,dx,dz,strength);}

    private static WeatherSimulation.Surface sample(double px,double pz) {
        int x=(int)Math.floor(px),z=(int)Math.floor(pz);
        long key=((long)x<<32)^(z&0xFFFFFFFFL);
        if(!mc.world.getChunkManager().isChunkLoaded(x>>4,z>>4)){SURFACES.remove(key);return null;}
        Sample old=SURFACES.get(key);
        if(old!=null&&tick-old.tick<5)return old.column.at(px-x,pz-z);
        if(queries>=48)return old==null||tick-old.tick>20?null:old.column.at(px-x,pz-z);
        queries++;
        int top=mc.world.getTopY(Heightmap.Type.MOTION_BLOCKING,x,z)-1;
        POS.set(x,top,z);
        var state=mc.world.getBlockState(POS);
        var fluid=state.getFluidState();
        var boxes=bounds(state.getCollisionShape(mc.world,POS));
        double fluidHeight=fluid.isEmpty()?Double.NEGATIVE_INFINITY:top+fluid.getHeight(mc.world,POS);
        boolean water=fluid.isIn(FluidTags.WATER);
        POS.set(x,top-1,z);
        var below=bounds(mc.world.getBlockState(POS).getCollisionShape(mc.world,POS));
        var column=new WeatherSurface.Column(top,boxes,below,fluidHeight,water);
        SURFACES.put(key,new Sample(column,tick));
        return column.at(px-x,pz-z);
    }
    private static WeatherSurface.Bounds[] bounds(net.minecraft.util.shape.VoxelShape shape) {
        return shape.getBoundingBoxes().stream().map(b->new WeatherSurface.Bounds(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ))
            .toArray(WeatherSurface.Bounds[]::new);
    }
    @EventHandler private static void onTick(TickEvent.Post event) {
        if(!active()){SIMULATION.clear();SURFACES.clear();world=null;return;}
        var camera=mc.gameRenderer.getCamera().getCameraPos();var c=Config.get();
        if(world!=mc.world||Math.hypot(camera.x-lastX,camera.z-lastZ)>c.weatherRadius.get()*3) {
            SIMULATION.clear();SURFACES.clear();world=mc.world;
        }
        lastX=camera.x;lastZ=camera.z;tick++;queries=0;
        if(SURFACES.size()>4096)SURFACES.clear();
        SIMULATION.tick(.05,camera.x,camera.y,camera.z,c.weatherStyle.get(),c.weatherDensity.get(),c.weatherRadius.get(),
            c.weatherWind.get(),c.weatherWindDirection.get(),c.weatherSpeed.get(),c.weatherImpacts.get(),EmberWeather::sample);
        if(c.weatherStyle.get()==WeatherSimulation.Kind.Rain&&c.weatherSound.get()&&c.weatherDensity.get()>0&&tick%16==0) {
            var roof=sample(camera.x,camera.z);
            if(roof!=null&&roof.height()<camera.y+12) {
                boolean sheltered=roof.height()>camera.y;
                mc.world.playSoundAtBlockCenterClient(BlockPos.ofFloored(camera),sheltered?SoundEvents.WEATHER_RAIN_ABOVE:SoundEvents.WEATHER_RAIN,
                    SoundCategory.WEATHER,(float)(c.weatherDensity.get()*(sheltered?.07:.2)),sheltered?.8f:1,false);
            }
        }
    }
    @EventHandler private static void onRender(Render3DEvent event) {
        if(!active()||world!=mc.world||SIMULATION.count()==0)return;
        var c=Config.get();var accent=EmberPalette.accent();
        MESH.clear();
        WeatherGeometry.build(MESH,SIMULATION,event.offsetX,event.offsetY,event.offsetZ,event.tickDelta,
            c.weatherRadius.get(),accent.r<<16|accent.g<<8|accent.b,c.weatherAccent.get());
        if(MESH.size==0)return;
        if(renderer==null)renderer=new Renderer3D(MeteorRenderPipelines.WORLD_COLORED_LINES_DEPTH,MeteorRenderPipelines.WORLD_COLORED_DEPTH);
        renderer.begin();CelestialRenderer.draw(MESH,renderer,event.offsetX,event.offsetY,event.offsetZ,0,0);renderer.render(event.matrices);
    }
}
