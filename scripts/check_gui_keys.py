"""Exercise the production keyboard hook before screen dispatch, without starting Minecraft."""
from pathlib import Path
import os
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
P = 'meteordevelopment/meteorclient/'
JAVA = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java')).parent
STUBS = {
    'net/minecraft/client/input/KeyInput.java': 'package net.minecraft.client.input;public record KeyInput(int key,int scancode,int modifiers){}',
    'net/minecraft/client/input/CharInput.java': 'package net.minecraft.client.input;public record CharInput(int codepoint,int modifiers){}',
    'net/minecraft/client/Keyboard.java': 'package net.minecraft.client;public class Keyboard{}',
    'net/minecraft/client/MinecraftClient.java': 'package net.minecraft.client;public class MinecraftClient{public Object currentScreen;public boolean isPaused(){return false;}}',
    'org/lwjgl/glfw/GLFW.java': 'package org.lwjgl.glfw;public class GLFW{public static final int GLFW_KEY_UNKNOWN=-1,GLFW_PRESS=1,GLFW_RELEASE=0,GLFW_REPEAT=2;}',
    'org/spongepowered/asm/mixin/Mixin.java': 'package org.spongepowered.asm.mixin;public @interface Mixin{Class<?>[] value();}',
    'org/spongepowered/asm/mixin/Shadow.java': 'package org.spongepowered.asm.mixin;public @interface Shadow{}',
    'org/spongepowered/asm/mixin/Final.java': 'package org.spongepowered.asm.mixin;public @interface Final{}',
    'org/spongepowered/asm/mixin/injection/At.java': 'package org.spongepowered.asm.mixin.injection;public @interface At{String value();}',
    'org/spongepowered/asm/mixin/injection/Inject.java': 'package org.spongepowered.asm.mixin.injection;public @interface Inject{String method();At at();boolean cancellable();}',
    'org/spongepowered/asm/mixin/injection/callback/CallbackInfo.java': 'package org.spongepowered.asm.mixin.injection.callback;public class CallbackInfo{public boolean cancelled;public void cancel(){cancelled=true;}}',
    'meteordevelopment/orbit/ICancellable.java': 'package meteordevelopment.orbit;public interface ICancellable{void setCancelled(boolean c);boolean isCancelled();}',
    P+'gui/WidgetScreen.java': 'package meteordevelopment.meteorclient.gui;import net.minecraft.client.input.KeyInput;public class WidgetScreen{public boolean owns;public int repeats;public boolean capturesKeyboardInput(){return owns;}public void keyRepeated(KeyInput input){repeats++;}}',
    P+'utils/Utils.java': 'package meteordevelopment.meteorclient.utils;public class Utils{public static boolean canUpdate(){return true;}}',
    P+'utils/misc/input/Input.java': 'package meteordevelopment.meteorclient.utils.misc.input;public class Input{public static boolean held;public static void setKeyState(int key,boolean down){held=down;}public static int getModifier(int key){return key==341?2:0;}}',
    P+'MeteorClient.java': '''package meteordevelopment.meteorclient;import meteordevelopment.meteorclient.events.Cancellable;
public class MeteorClient{public static final Bus EVENT_BUS=new Bus();public static class Bus{public int posts;public boolean cancel;public <T extends Cancellable>T post(T event){posts++;event.setCancelled(cancel);return event;}}}''',
}
HARNESS = '''import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.*;
import meteordevelopment.meteorclient.mixin.KeyboardMixin;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.KeyInput;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
public class GuiKeyChecks {
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception{
  var client=new MinecraftClient();var hook=new KeyboardMixin(){};
  var field=KeyboardMixin.class.getDeclaredField("client");field.setAccessible(true);field.set(hook,client);
  var bus=MeteorClient.EVENT_BUS;var menuKey=new KeyInput(82,0,0);
  hook.onKey(1,1,menuKey,new CallbackInfo());check(bus.posts==1,"Menu shortcut remains available in game");
  var screen=new WidgetScreen();client.currentScreen=screen;
  hook.onKey(1,1,menuKey,new CallbackInfo());check(bus.posts==2,"Idle GUI still allows the close shortcut");
  // Focus changes immediately, with the old per-render flag still true.
  screen.owns=true;check(GuiKeyEvents.canUseKeys,"No intervening render has suppressed global keys");
  for(int action:new int[]{1,2,0}){
   var callback=new CallbackInfo();hook.onKey(1,action,menuKey,callback);
   check(bus.posts==2,"A focused editor must not publish the menu shortcut");
   check(!callback.cancelled,"Keys must remain available to Minecraft's screen dispatch");
    check(meteordevelopment.meteorclient.utils.misc.input.Input.held==(action!=0),"Focused input still tracks held and released keys");
  }
  check(screen.repeats==1,"Focused widget still receives repeated keys");
  screen.owns=false;hook.onKey(1,1,menuKey,new CallbackInfo());check(bus.posts==3,"Closing the editor restores the menu shortcut immediately");
  GuiKeyEvents.canUseKeys=false;hook.onKey(1,1,menuKey,new CallbackInfo());check(bus.posts==3,"Existing native text-box suppression remains intact");
  GuiKeyEvents.canUseKeys=true;bus.cancel=true;var cancelled=new CallbackInfo();hook.onKey(1,1,menuKey,cancelled);check(cancelled.cancelled,"Other global event cancellation still works");
  hook.onKey(1,1,new KeyInput(-1,0,0),new CallbackInfo());check(bus.posts==4,"Unknown keys remain ignored");
  System.out.println("GUI keyboard routing passed: immediate focus, press/repeat/release, screen dispatch, repeat delivery, normal shortcuts, native text boxes and event cancellation.");
 }
}'''


def main():
    with tempfile.TemporaryDirectory(prefix='ember-gui-keys-') as directory:
        temp = Path(directory)
        sources = temp / 'src'
        for path, body in STUBS.items():
            target = sources / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(body, encoding='utf-8')
        for name in ('mixin/KeyboardMixin', 'gui/GuiKeyEvents', 'events/Cancellable',
                     'events/meteor/KeyEvent', 'events/meteor/CharTypedEvent', 'utils/misc/input/KeyAction'):
            target = sources / (P + name + '.java')
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(ROOT / 'src/main/java' / (P + name + '.java'), target)
        (sources / 'GuiKeyChecks.java').write_text(HARNESS, encoding='utf-8')
        classes = temp / 'classes'
        subprocess.run([str(JAVA / 'javac'), '-encoding', 'UTF-8', '-proc:none', '-d', str(classes),
                        *map(str, sources.rglob('*.java'))], check=True)
        subprocess.run([str(JAVA / 'java'), '-cp', str(classes), 'GuiKeyChecks'], check=True)


if __name__ == '__main__':
    main()
