"""Run production Microsoft request/cancellation lifecycle against a deterministic auth provider."""
from pathlib import Path
import os, shutil, subprocess, tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else Path(shutil.which('java')).parent
P = 'meteordevelopment/meteorclient/'
stubs = {
    P+'MeteorClient.java': 'package meteordevelopment.meteorclient;public class MeteorClient{public static final Log LOG=new Log();public static class Log{public void error(String text,Exception e){}}}',
    P+'utils/network/MeteorExecutor.java': '''package meteordevelopment.meteorclient.utils.network;public class MeteorExecutor{public static final java.util.concurrent.ExecutorService executor=java.util.concurrent.Executors.newCachedThreadPool(r->{Thread t=new Thread(r);t.setDaemon(true);return t;});}''',
    'net/minecraft/util/Util.java': '''package net.minecraft.util;public class Util{public static final java.util.List<String> opened=new java.util.concurrent.CopyOnWriteArrayList<>();public static Util getOperatingSystem(){return new Util();}public void open(String url){opened.add(url);}}''',
    'org/jspecify/annotations/Nullable.java': 'package org.jspecify.annotations;public @interface Nullable{}',
    'net/raphimc/minecraftauth/MinecraftAuth.java': 'package net.raphimc.minecraftauth;public class MinecraftAuth{public static Object createHttpClient(){return new Object();}}',
    'net/raphimc/minecraftauth/msa/data/MsaConstants.java': 'package net.raphimc.minecraftauth.msa.data;public class MsaConstants{public static final String JAVA_TITLE_ID="test",SCOPE_TITLE_AUTH="test";}',
    'net/raphimc/minecraftauth/msa/model/MsaApplicationConfig.java': 'package net.raphimc.minecraftauth.msa.model;public record MsaApplicationConfig(String id,String scope){}',
    'net/raphimc/minecraftauth/msa/model/MsaDeviceCode.java': 'package net.raphimc.minecraftauth.msa.model;public record MsaDeviceCode(String url){public String getDirectVerificationUri(){return url;}}',
    'net/raphimc/minecraftauth/msa/model/MsaToken.java': 'package net.raphimc.minecraftauth.msa.model;public record MsaToken(String token){public String getRefreshToken(){return token;}}',
    'net/raphimc/minecraftauth/msa/service/impl/DeviceCodeMsaAuthService.java': 'package net.raphimc.minecraftauth.msa.service.impl;public class DeviceCodeMsaAuthService{public DeviceCodeMsaAuthService(Object o){}}',
    'net/raphimc/minecraftauth/java/model/MinecraftToken.java': 'package net.raphimc.minecraftauth.java.model;public class MinecraftToken{public String getToken(){return "minecraft-token";}}',
    'net/raphimc/minecraftauth/java/model/MinecraftProfile.java': 'package net.raphimc.minecraftauth.java.model;public class MinecraftProfile{public java.util.UUID getId(){return new java.util.UUID(1,2);}public String getName(){return "TestPlayer";}}',
    'net/raphimc/minecraftauth/java/AuthProbe.java': '''package net.raphimc.minecraftauth.java;
public class AuthProbe{
 public static final java.util.concurrent.BlockingQueue<Attempt> pending=new java.util.concurrent.LinkedBlockingQueue<>();
 public static class Attempt{public final String id;public boolean fail;public final java.util.concurrent.CountDownLatch started=new java.util.concurrent.CountDownLatch(1),url=new java.util.concurrent.CountDownLatch(1),finish=new java.util.concurrent.CountDownLatch(1),completed=new java.util.concurrent.CountDownLatch(1);public Attempt(String id){this.id=id;}public void await(java.util.concurrent.CountDownLatch gate){boolean ready=false;while(!ready){try{gate.await();ready=true;}catch(InterruptedException ignored){}}}}
}''',
    'net/raphimc/minecraftauth/java/JavaAuthManager.java': '''package net.raphimc.minecraftauth.java;
import net.raphimc.minecraftauth.msa.model.*;import net.raphimc.minecraftauth.java.model.*;
public class JavaAuthManager{
 AuthProbe.Attempt attempt;
 public static JavaAuthManager create(Object client){return new JavaAuthManager();}public JavaAuthManager msaApplicationConfig(MsaApplicationConfig config){return this;}
 public JavaAuthManager login(java.util.function.Function<Object,?> factory,java.util.function.Consumer<MsaDeviceCode> code)throws InterruptedException{attempt=AuthProbe.pending.take();attempt.started.countDown();attempt.await(attempt.url);if(attempt.fail)throw new IllegalStateException("test failure");code.accept(new MsaDeviceCode("https://login.example/"+attempt.id));return this;}
 public JavaAuthManager login(String token){attempt=new AuthProbe.Attempt("refresh");attempt.finish.countDown();return this;}
 public TokenRef getMsaToken(){return new TokenRef();}public class TokenRef{public MsaToken getUpToDate(){attempt.await(attempt.finish);attempt.completed.countDown();return new MsaToken("refresh-"+attempt.id);}}
 public MinecraftRef getMinecraftToken(){return new MinecraftRef();}public static class MinecraftRef{public MinecraftToken getUpToDate(){return new MinecraftToken();}}
 public ProfileRef getMinecraftProfile(){return new ProfileRef();}public static class ProfileRef{public MinecraftProfile getUpToDate(){return new MinecraftProfile();}}
}''',
}
HARNESS = '''import java.util.concurrent.*;import java.util.concurrent.atomic.*;import net.raphimc.minecraftauth.java.AuthProbe;import meteordevelopment.meteorclient.systems.accounts.MicrosoftLogin;
public class MicrosoftRequestChecks{
 static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
 static void ready(CountDownLatch gate)throws Exception{require(gate.await(10,TimeUnit.SECONDS),"Timed out waiting for fake auth provider");}
 public static void main(String[] args)throws Exception{
  var old=new AuthProbe.Attempt("old");AuthProbe.pending.add(old);var oldCalled=new CountDownLatch(1);
  var oldUrl=MicrosoftLogin.startLogin(t->oldCalled.countDown());ready(old.started);require(!oldUrl.isDone(),"Startup must return before the network supplies a URL");
  MicrosoftLogin.cancelLogin();require(oldUrl.isCancelled(),"Cancel unblocks an outstanding URL request");
  var next=new AuthProbe.Attempt("next");AuthProbe.pending.add(next);var completed=new CompletableFuture<String>();
  var url=MicrosoftLogin.startLogin(completed::complete);ready(next.started);next.url.countDown();require(url.get(10,TimeUnit.SECONDS).equals("https://login.example/next"),"Expose the device-code URL asynchronously");
  old.url.countDown();old.finish.countDown();ready(old.completed);require(!oldCalled.await(200,TimeUnit.MILLISECONDS),"An uninterruptible old worker must remain cancelled after starting a new request");
  require(net.minecraft.util.Util.opened.stream().noneMatch(s->s.endsWith("old")),"Cancelled workers cannot open a stale browser link");
  next.finish.countDown();require(completed.get(10,TimeUnit.SECONDS).equals("refresh-next"),"Active request finishes normally");
  var failed=new AuthProbe.Attempt("failed");failed.fail=true;AuthProbe.pending.add(failed);var failure=new CountDownLatch(1);var failedUrl=MicrosoftLogin.startLogin(token->{require(token==null,"Failed auth callback has no credential");failure.countDown();});ready(failed.started);failed.url.countDown();
  try{failedUrl.get(10,TimeUnit.SECONDS);throw new AssertionError("Expected failed URL request");}catch(ExecutionException expected){}ready(failure);
  MicrosoftLogin.cancelLogin();var refreshed=MicrosoftLogin.login("test");require(refreshed!=null&&refreshed.username().equals("TestPlayer"),"Existing refresh-token login remains available");
  System.out.println("Microsoft login passed: nonblocking URL readiness, pending cancellation, overlapping workers with ignored interrupts, stale callback/browser suppression, provider failure and refresh login compatibility.");
 }
}'''


def main():
    with tempfile.TemporaryDirectory(prefix='ember-account-login-') as temp:
        temp=Path(temp);sources=temp/'src';classes=temp/'classes'
        for name,body in stubs.items():
            path=sources/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(body,encoding='utf-8')
        path=sources/(P+'systems/accounts/MicrosoftLogin.java');path.parent.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(ROOT/'src/main/java'/(P+'systems/accounts/MicrosoftLogin.java'),path)
        (sources/'MicrosoftRequestChecks.java').write_text(HARNESS,encoding='utf-8')
        subprocess.run([str(JAVA/'javac'),'-encoding','UTF-8','-d',str(classes),*map(str,sources.rglob('*.java'))],check=True)
        subprocess.run([str(JAVA/'java'),'-cp',str(classes),'MicrosoftRequestChecks'],check=True)


if __name__=='__main__':
    main()
