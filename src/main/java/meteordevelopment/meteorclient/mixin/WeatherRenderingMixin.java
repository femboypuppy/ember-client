package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.utils.render.EmberWeather;
import net.minecraft.client.render.WeatherRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WeatherRendering.class)
public abstract class WeatherRenderingMixin {
    @Inject(method="addParticlesAndSound",at=@At("HEAD"),cancellable=true)
    private void ember$localWeatherParticles(CallbackInfo ci){if(EmberWeather.active())ci.cancel();}
    @Inject(method="buildPrecipitationPieces",at=@At("HEAD"),cancellable=true)
    private void ember$localPrecipitation(CallbackInfo ci){if(EmberWeather.active())ci.cancel();}
}
