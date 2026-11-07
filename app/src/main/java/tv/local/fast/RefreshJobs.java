package tv.local.fast;

import android.content.Context;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.IntConsumer;

/** Refreshes outlive individual screens; one writer at a time also protects source changes. */
final class RefreshJobs {
    private static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private static final Map<String,Future<Integer>> pending=new HashMap<>();
    private static final Map<String,String> labels=new java.util.LinkedHashMap<>();
    private static final Map<String,Long> revisions=new HashMap<>();
    private static String lastResult="";
    private static long resultUntil;
    static synchronized long revision(String type){return revisions.getOrDefault(type,0L);}
    static synchronized String status(){
        if(!labels.isEmpty())return String.join(" · ",labels.values());
        return android.os.SystemClock.elapsedRealtime()<resultUntil?lastResult:"";
    }
    static String error(Throwable error){
        while((error instanceof ExecutionException||error instanceof java.util.concurrent.CompletionException)&&error.getCause()!=null)error=error.getCause();
        if(error instanceof java.net.SocketTimeoutException)return "Provider timed out. Try Refresh again.";
        if(error instanceof java.io.IOException&&error.getMessage()!=null)return error.getMessage();
        return "Could not refresh. Check the source and try again.";
    }
    private static synchronized void update(String key,String value){if(labels.containsKey(key))labels.put(key,value);}


    static Future<Integer> library(Context context,String database,Source source,String kind,IntConsumer progress) throws Exception{
        return submit(context,database,source,"library:"+kind, false,progress);
    }
    static Future<Integer> live(Context context,String database,Source source,boolean replacing) throws Exception{
        return submit(context,database,source,"live",replacing,count->{});
    }
    private static synchronized Future<Integer> submit(Context context,String database,Source source,String type,boolean replacing,IntConsumer progress) throws Exception{
        String key=database+"\n"+type+"\n"+replacing+"\n"+source.json();
        Future<Integer> existing=pending.get(key);if(existing!=null)return existing;
        Context app=context.getApplicationContext();
        String label=type.equals("live")?"Live TV":type.equals("library:movie")?"Films":"Series";
        labels.put(key,label+": queued");
        FutureTask<Integer> task=new FutureTask<Integer>(()->{
            update(key,label+": refreshing…");
            Catalog catalog=new Catalog(app,database);
            try{
                if(type.startsWith("library:")){
                    Source saved=catalog.source();
                    if(saved==null||!saved.json().equals(source.json()))throw new java.io.IOException("The source changed before refresh started.");
                    return LibraryProvider.refresh(catalog,source,type.substring(8),count->{update(key,count<0?label+": updating saved catalog…":label+": "+count+" titles");progress.accept(count);});
                }
                if(!replacing){Source saved=catalog.source();
                    if(saved==null||!saved.json().equals(source.json()))throw new java.io.IOException("The source changed before refresh started.");
                }
                return Provider.refresh(catalog,source,replacing);
            }finally{catalog.close();}
        }){
            @Override protected void done(){synchronized(RefreshJobs.class){
                pending.remove(key,this);labels.remove(key);
                try{get();revisions.put(type,revision(type)+1);lastResult=label+": refresh complete";}catch(Exception e){lastResult=label+": "+error(e);}
                resultUntil=android.os.SystemClock.elapsedRealtime()+15000;
            }}
        };
        pending.put(key,task);worker.execute(task);return task;
    }
}
