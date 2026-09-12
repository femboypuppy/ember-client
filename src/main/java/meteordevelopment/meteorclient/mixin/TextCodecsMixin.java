package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.Codec;
import meteordevelopment.meteorclient.utils.network.ServerComponentCodec;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Function;

/** Tags text decoded from a server. Verified: one Codec.recursive call site in <clinit>. */
@Mixin(TextCodecs.class)
public abstract class TextCodecsMixin {
    @WrapOperation(
        method = "<clinit>",
        at = @At(value = "INVOKE", target = "Lcom/mojang/serialization/Codec;recursive(Ljava/lang/String;Ljava/util/function/Function;)Lcom/mojang/serialization/Codec;")
    )
    private static Codec<Text> ember$tagServerComponents(String name, Function<Codec<Text>, Codec<Text>> body, Operation<Codec<Text>> original) {
        return new ServerComponentCodec(original.call(name, body));
    }
}
