package meteordevelopment.meteorclient.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import meteordevelopment.meteorclient.mixininterface.IServerComponent;
import meteordevelopment.meteorclient.utils.network.KeyResolutionProtection;
import net.minecraft.text.KeybindTextContent;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Supplier;

/**
 * Reports the default vanilla key for vanilla binds and a plain translation for mod binds,
 * so a server cannot read back custom binds or detect the mod.
 *
 * Verified against the bytecode: the call sits in getTranslated() (not getNestedComponent
 * as on newer versions), and this class exposes getKey() rather than a name field to shadow.
 */
@Mixin(KeybindTextContent.class)
public abstract class KeybindTextContentMixin implements IServerComponent {
    @Shadow
    public abstract String getKey();

    @Unique
    private boolean ember$fromServer;

    @Override
    public void ember$markFromServer() {
        ember$fromServer = true;
    }

    @Override
    public boolean ember$isFromServer() {
        return ember$fromServer;
    }

    @WrapOperation(
        method = "getTranslated",
        at = @At(value = "INVOKE", target = "Ljava/util/function/Supplier;get()Ljava/lang/Object;")
    )
    private Object ember$protectKeybind(Supplier<?> supplier, Operation<Object> original) {
        Object resolved = original.call(supplier);
        return ember$fromServer ? KeyResolutionProtection.keybind(getKey(), (Text) resolved) : resolved;
    }
}
