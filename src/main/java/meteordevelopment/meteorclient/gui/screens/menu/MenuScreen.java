package meteordevelopment.meteorclient.gui.screens.menu;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.sound.MusicSound;
import net.minecraft.sound.MusicType;
import net.minecraft.text.Text;

/** Native screen root. Mouse input and paint use the same virtual-pixel coordinate system. */
public abstract class MenuScreen extends Screen {
    protected final MenuCanvas canvas=new MenuCanvas();
    private final MenuAnimation.Clock clock=new MenuAnimation.Clock();
    private int resourceGeneration=-1;
    protected double unit=1,vw=1280,vh=720,dt;
    protected MenuScreen(String title){super(Text.literal(title));}
    protected double uiScale(){return ConfigManager.get().uiScale;}
    @Override protected void init(){clock.reset();canvas.reset();MenuLanguage.reload();layout();}
    protected void layout(){}
    protected void viewport(){unit=Math.max(.05,Math.min(width/MenuTheme.WIDTH,height/MenuTheme.HEIGHT)*uiScale());vw=width/unit;vh=height/unit;}
    @Override public final void render(DrawContext context,int mouseX,int mouseY,float delta){
        viewport();dt=clock.tick();canvas.context=context;canvas.opacity=1;
        if(resourceGeneration!=MenuRuntime.resourceGeneration){resourceGeneration=MenuRuntime.resourceGeneration;canvas.reset();MenuLanguage.reload();layout();}
        context.getMatrices().pushMatrix();context.getMatrices().scale((float)unit,(float)unit);
        paint(mouseX/unit,mouseY/unit);context.getMatrices().popMatrix();
    }
    protected abstract void paint(double mx,double my);
    @Override public void renderBackground(DrawContext context,int mouseX,int mouseY,float delta){}
    @Override public boolean shouldPause(){return false;}
    @Override public void tick(){MenuAudio.tick();}
    @Override public MusicSound getMusic(){return MusicType.MENU;}
    @Override public void removed(){ConfigManager.flush();MenuAudio.refreshAfterScreenChange();}
}
