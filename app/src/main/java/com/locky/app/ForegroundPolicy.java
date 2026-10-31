package com.locky.app;
import java.util.*;
/** Chooses actual app windows independently of launcher intents or event senders. */
final class ForegroundPolicy {
    static final class Window {
        final String pkg;final int type,layer;final boolean focused,active,pip,covering;
        Window(String pkg,int type,boolean focused,boolean active,int layer,boolean pip){this(pkg,type,focused,active,layer,pip,false);}
        Window(String pkg,int type,boolean focused,boolean active,int layer,boolean pip,boolean covering){this.pkg=pkg;this.type=type;this.focused=focused;this.active=active;this.layer=layer;this.pip=pip;this.covering=covering;}
    }
    static final class Result {final String pkg;final boolean paused;Result(String pkg,boolean paused){this.pkg=pkg;this.paused=paused;}}
    static Result select(List<Window> windows,Set<String> system) {
        for(Window w:windows)if(w.type==3&&(w.focused||w.active))return new Result("",true);
        Window best=null;
        for(Window w:windows)if(w.type==1&&!w.pip&&!w.pkg.isEmpty()) {
            if(best==null||score(w)>score(best)||(score(w)==score(best)&&w.layer>best.layer))best=w;
        }
        for(Window w:windows)if(w.type==3&&w.covering&&(best==null||w.layer>best.layer))return new Result("",true);
        if(best==null)return new Result("",false);
        return system.contains(best.pkg)?new Result("",true):new Result(best.pkg,false);
    }
    private static int score(Window w){return w.focused?2:w.active?1:0;}
    /** An actual app state event plus focused, active full window can bypass Overview settling. */
    static boolean confirmedEntry(List<Window> windows,Result result,String eventPackage,boolean appStateEvent){
        return appStateEvent&&!result.paused&&!result.pkg.isEmpty()&&result.pkg.equals(eventPackage)
            &&windows.stream().anyMatch(w->w.type==1&&w.pkg.equals(result.pkg)&&w.focused&&w.active&&w.covering&&!w.pip);
    }
}
