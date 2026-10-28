package tv.local.fast;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.security.MessageDigest;
import java.util.concurrent.*;

/**
 * Poster loader for the film and series grids. Deliberately small: this TV has 1 GB of RAM, so bitmaps are
 * decoded straight to the cell size in RGB_565 and both caches are bounded. No image library is pulled in.
 */
final class Posters {
    private static final int DISK_FILES=600;
    private static final long MAX_IMAGE_BYTES=2L*1024*1024;
    private static final Bitmap MISSING=Bitmap.createBitmap(1,1,Bitmap.Config.ALPHA_8);// negative cache, never drawn
    private static Posters instance;

    private final LruCache<String,Bitmap> memory;
    private final ExecutorService io=Executors.newFixedThreadPool(3,r->{Thread t=new Thread(r,"poster");t.setPriority(Thread.MIN_PRIORITY);return t;});
    private final Handler main=new Handler(Looper.getMainLooper());
    private final File dir;

    static synchronized Posters get(Context context){
        if(instance==null)instance=new Posters(context.getApplicationContext());
        return instance;
    }
    private Posters(Context context){
        int budget=(int)Math.min(16L*1024*1024,Runtime.getRuntime().maxMemory()/8);
        memory=new LruCache<String,Bitmap>(budget){
            @Override protected int sizeOf(String key,Bitmap value){return value==MISSING?1:value.getByteCount();}
        };
        dir=new File(context.getCacheDir(),"posters");
        if(!dir.isDirectory()&&!dir.mkdirs())android.util.Log.w("ClaireTV","poster_cache_unavailable");
    }
    /** Providers return empty strings, "null" and bare whitespace for titles that have no artwork. */
    static String clean(String url){
        if(url==null)return "";
        String s=url.trim();
        if(s.isEmpty()||s.equalsIgnoreCase("null")||s.length()>1000)return "";
        return s.startsWith("http://")||s.startsWith("https://")?s:"";
    }
    /**
     * Binds a poster to a recycled view. The url is stored as the view's tag so a slow download that lands
     * after the cell has been reused for another title is discarded instead of showing the wrong poster.
     */
    void load(ImageView view,String url,int width,int height,int placeholder){
        view.setTag(R.id.poster_url,url);
        Bitmap cached=url.isEmpty()?null:memory.get(url);
        if(cached!=null){apply(view,cached,placeholder);return;}
        view.setImageDrawable(null);
        view.setBackgroundResource(placeholder);
        if(url.isEmpty())return;
        io.execute(()->{
            if(!url.equals(view.getTag(R.id.poster_url)))return;// recycled before we started
            Bitmap bitmap=fetch(url,width,height);
            memory.put(url,bitmap==null?MISSING:bitmap);
            main.post(()->{if(url.equals(view.getTag(R.id.poster_url)))apply(view,bitmap==null?MISSING:bitmap,placeholder);});
        });
    }
    private void apply(ImageView view,Bitmap bitmap,int placeholder){
        if(bitmap==MISSING){view.setImageDrawable(null);view.setBackgroundResource(placeholder);return;}
        view.setBackground(null);view.setImageBitmap(bitmap);
    }
    private Bitmap fetch(String url,int width,int height){
        File file=new File(dir,name(url));
        byte[] body=read(file);
        if(body==null){
            try{body=Provider.bytes(url,MAX_IMAGE_BYTES);}
            catch(Exception e){return null;}
            write(file,body);
        }
        return decode(body,width,height);
    }
    private static Bitmap decode(byte[] body,int width,int height){
        try{
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(body,0,body.length,bounds);
            if(bounds.outWidth<=0||bounds.outHeight<=0)return null;
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inPreferredConfig=Bitmap.Config.RGB_565;// posters have no alpha; halves the memory per cell
            options.inSampleSize=sample(bounds.outWidth,bounds.outHeight,Math.max(1,width),Math.max(1,height));
            return BitmapFactory.decodeByteArray(body,0,body.length,options);
        }catch(Throwable oom){return null;}
    }
    static int sample(int sourceWidth,int sourceHeight,int width,int height){
        int size=1;
        while(sourceWidth/(size*2)>=width&&sourceHeight/(size*2)>=height)size*=2;
        return size;
    }
    private static byte[] read(File file){
        if(!file.isFile())return null;
        try(InputStream in=new FileInputStream(file)){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))>0)out.write(buffer,0,n);
            file.setLastModified(System.currentTimeMillis());
            return out.size()==0?null:out.toByteArray();
        }catch(IOException e){return null;}
    }
    private void write(File file,byte[] body){
        try(OutputStream out=new FileOutputStream(file)){out.write(body);}catch(IOException e){return;}
        trim();
    }
    /** ponytail: oldest-first trim by modification time, run after a miss. A real LRU index is not worth it for 600 files. */
    private void trim(){
        File[] files=dir.listFiles();
        if(files==null||files.length<=DISK_FILES)return;
        java.util.Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));
        for(int i=0;i<files.length-DISK_FILES;i++)if(!files[i].delete())return;
    }
    private static String name(String url){
        try{
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(url.getBytes("UTF-8"));
            StringBuilder out=new StringBuilder(32);
            for(int i=0;i<16;i++)out.append(Character.forDigit((hash[i]>>4)&15,16)).append(Character.forDigit(hash[i]&15,16));
            return out.toString();
        }catch(Exception impossible){throw new AssertionError(impossible);}
    }
}
