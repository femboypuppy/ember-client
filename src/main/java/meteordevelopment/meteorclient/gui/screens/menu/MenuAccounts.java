package meteordevelopment.meteorclient.gui.screens.menu;

import meteordevelopment.meteorclient.systems.accounts.*;
import meteordevelopment.meteorclient.systems.accounts.Account;
import meteordevelopment.meteorclient.systems.accounts.types.*;
import meteordevelopment.meteorclient.utils.network.MeteorExecutor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Uuids;
import java.util.ArrayList;
import java.util.List;

/** Reuses Ember's existing account store and authentication; the menu JSON never holds credentials. */
public final class MenuAccounts {
    public record Entry(Account<?> account,String name,String type) {}
    public final List<Entry> entries=new ArrayList<>();
    public String status="Choose an account, or add another.";
    public boolean busy,browser,error;
    public int revision;
    private int stored=-1;
    private volatile long request;
    private final MinecraftClient mc=MinecraftClient.getInstance();
    public void refresh(){if(Accounts.get()==null||stored==Accounts.get().size())return;stored=Accounts.get().size();entries.clear();for(Account<?> account:Accounts.get())entries.add(new Entry(account,safeName(account),account.getType()==AccountType.Cracked?"Offline":account.getType().name()));revision++;}
    private static String safeName(Account<?> account){String cached=account.getCache().username;return cached!=null&&!cached.isBlank()?cached:account.getType()==AccountType.Cracked?account.getUsername():account.getType()+" account";}
    public boolean current(Entry entry){var id=mc.getSession().getUuidOrNull();if(id==null)return false;String cached=entry.account.getCache().uuid;
        return cached!=null&&!cached.isBlank()?cached.replace("-","").equalsIgnoreCase(id.toString().replace("-","")):entry.account.getType()==AccountType.Cracked&&Uuids.getOfflinePlayerUuid(entry.name).equals(id);}
    public void signIn(Account<?> account,boolean add){
        if(busy||mc.world!=null)return;long attempt=++request;busy=true;message("Signing in...",false);
        MeteorExecutor.execute(()->{try{
            if(!account.fetchInfo()){fail(attempt);return;}
            mc.execute(()->{if(request!=attempt||mc.world!=null){busy=false;return;}try{
                // Session/client services are changed on the client thread after network work completes.
                if(!account.login()){fail(attempt);return;}
                if(add){Account<?> duplicate=null;for(Account<?> existing:Accounts.get()){
                    String a=existing.getCache().uuid,b=account.getCache().uuid;
                    if(existing.getType()==account.getType()&&((a!=null&&!a.isBlank()&&b!=null&&a.replace("-","").equalsIgnoreCase(b.replace("-","")))||(account.getType()==AccountType.Cracked&&safeName(existing).equalsIgnoreCase(safeName(account))))){duplicate=existing;break;}}
                    if(duplicate!=null)Accounts.get().remove(duplicate);Accounts.get().add(account);
                }
                Accounts.get().save();stored=-1;refresh();message("Signed in successfully.",false);
            }catch(Exception exception){message("Could not sign in. Please try again.",true);}finally{busy=false;revision++;}});
        }catch(Exception exception){fail(attempt);}});
    }
    private void fail(long attempt){mc.execute(()->{if(request==attempt){busy=false;browser=false;message("Could not sign in. Please try again.",true);}});}
    public void microsoft(){
        if(busy||mc.world!=null)return;long attempt=++request;busy=browser=true;message("Continue in your browser...",false);
        MicrosoftLogin.startLogin(token->mc.execute(()->{if(request!=attempt)return;busy=browser=false;if(token==null){message("Sign-in cancelled.",true);return;}signIn(new MicrosoftAccount(token),true);})).whenComplete((url,error)->{if(error!=null)fail(attempt);});
    }
    public void add(AccountType type,String value){
        String clean=value.strip();if(type==AccountType.Microsoft){microsoft();return;}
        if(type==AccountType.Cracked&&!clean.matches("[A-Za-z0-9_]{1,16}")){message("Use 1-16 letters, numbers or underscores.",true);return;}
        if(clean.isEmpty()){message("Enter the account details first.",true);return;}
        signIn(switch(type){case Cracked->new CrackedAccount(clean);case Session->new SessionAccount(clean);case TheAltening->new TheAlteningAccount(clean);default->throw new IllegalArgumentException();},true);
    }
    public void remove(Entry entry){if(busy)return;Accounts.get().remove(entry.account);stored=-1;refresh();message("Saved account removed. Your session stays active.",false);}
    public void cancelBrowser(){if(browser){request++;MicrosoftLogin.cancelLogin();busy=browser=false;message("Sign-in cancelled.",false);}}
    public void message(String message,boolean failed){status=message;error=failed;revision++;}
    public boolean canLeave(){if(busy&&!browser){message("Finishing sign-in...",false);return false;}cancelBrowser();return true;}
}
