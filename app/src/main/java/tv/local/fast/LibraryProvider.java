package tv.local.fast;

import android.content.ContentValues;
import android.database.sqlite.*;
import android.util.JsonReader;
import android.util.JsonToken;
import org.json.JSONObject;
import java.io.*;

final class LibraryProvider {
    static int refresh(Catalog catalog,Source source,String kind,java.util.function.IntConsumer progress) throws Exception{
        if(!source.type.equals("xtream"))throw new IOException("Films and Series need an Xtream source. Your M3U source remains available in Live TV.");
        if(!kind.equals("movie")&&!kind.equals("series"))throw new IllegalArgumentException("Unknown library.");
        long started=android.os.SystemClock.elapsedRealtime();
        try(ImportStage stage=new ImportStage(catalog)){
            SQLiteDatabase incoming=stage.database();
            incoming.execSQL("CREATE TABLE media_items(kind TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,category TEXT NOT NULL,extension TEXT NOT NULL,summary TEXT NOT NULL,position INTEGER NOT NULL,image TEXT NOT NULL,PRIMARY KEY(kind,id))");
            incoming.execSQL("CREATE TABLE media_groups(kind TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(kind,id))");
            incoming.beginTransaction();int count;long groupsDone,itemsDone;
            try{
                importGroups(incoming,source,kind);groupsDone=android.os.SystemClock.elapsedRealtime();
                count=importItems(incoming,source,kind,progress);itemsDone=android.os.SystemClock.elapsedRealtime();
                incoming.setTransactionSuccessful();
            }finally{incoming.endTransaction();}
            progress.accept(-1);
            SQLiteDatabase d=catalog.getWritableDatabase();stage.attach(d);
            try{
                d.beginTransaction();
                try{
                    d.delete("media_items","kind=?",new String[]{kind});d.delete("media_groups","kind=?",new String[]{kind});
                    d.execSQL("INSERT INTO media_items SELECT * FROM imported.media_items");
                    d.execSQL("INSERT INTO media_groups SELECT * FROM imported.media_groups");
                    ContentValues status=new ContentValues();status.put("kind",kind);status.put("updated",System.currentTimeMillis());d.insertWithOnConflict("media_status",null,status,SQLiteDatabase.CONFLICT_REPLACE);
                    d.execSQL("DELETE FROM media_favorites WHERE kind=? AND id NOT IN (SELECT id FROM media_items WHERE kind=?)",new Object[]{kind,kind});
                    d.delete("media_cache","kind=?",new String[]{kind});d.setTransactionSuccessful();
                }finally{d.endTransaction();}
            }finally{d.execSQL("DETACH DATABASE imported");}
            android.util.Log.i("ClaireTV","library_refresh kind="+kind+" titles="+count+" categories_ms="+(groupsDone-started)+" download_import_ms="+(itemsDone-groupsDone)+" apply_ms="+(android.os.SystemClock.elapsedRealtime()-itemsDone));
            return count;
        }
    }
    private static boolean numericId(String value){
        if(value.isEmpty())return false;
        for(int i=0;i<value.length();i++)if(value.charAt(i)<'0'||value.charAt(i)>'9')return false;
        return true;
    }
    private static String scalar(JsonReader reader) throws IOException{
        if(reader.peek()==JsonToken.NULL){reader.nextNull();return "";}
        if(reader.peek()==JsonToken.STRING||reader.peek()==JsonToken.NUMBER)return reader.nextString();
        reader.skipValue();return "";
    }
    private static void importGroups(SQLiteDatabase d,Source source,String kind) throws Exception{
        String action=kind.equals("movie")?"get_vod_categories":"get_series_categories";
        try(Provider.Response r=new Provider.Response(source.api(action));JsonReader j=new JsonReader(r.reader)){
            if(j.peek()!=JsonToken.BEGIN_ARRAY)throw new IOException("The provider did not return library categories. Check that your subscription includes this library.");
            j.beginArray();int named=0,total=0;while(j.hasNext()){
                String id="",name="";j.beginObject();while(j.hasNext()){String key=j.nextName();if(key.equals("category_id"))id=scalar(j);else if(key.equals("category_name"))name=scalar(j);else j.skipValue();}j.endObject();
                total++;if(id.isEmpty()||name.isEmpty())continue;named++;
                ContentValues v=new ContentValues();v.put("kind",kind);v.put("id",id);v.put("name",name);d.insertWithOnConflict("media_groups",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            }j.endArray();
            android.util.Log.i("ClaireTV",kind+"_categories_returned="+total+" named="+named);
        }
    }
    static int importItems(SQLiteDatabase d,Source source,String kind,java.util.function.IntConsumer progress) throws Exception{
        String action=kind.equals("movie")?"get_vod_streams":"get_series";
        try(Provider.Response r=new Provider.Response(source.api(action),Provider.CATALOG_BYTES)){return readItems(d,r.reader,kind,progress,false);}
    }
    static int readItems(SQLiteDatabase d,Reader input,String kind,java.util.function.IntConsumer progress) throws Exception{
        return readItems(d,input,kind,progress,false);
    }
    private static int readItems(SQLiteDatabase d,Reader input,String kind,java.util.function.IntConsumer progress,boolean staging) throws Exception{
        String sql=staging?"INSERT OR REPLACE INTO refresh_items(id,name,category,extension,summary,position,image) VALUES(?,?,?,?,?,?,?)":"INSERT OR REPLACE INTO media_items(kind,id,name,category,extension,summary,position,image) VALUES(?,?,?,?,?,?,?,?)";
        try(JsonReader j=new JsonReader(input);SQLiteStatement row=d.compileStatement(sql)){
            if(j.peek()!=JsonToken.BEGIN_ARRAY)throw new IOException("The provider did not return this library. Try Refresh, or check your subscription.");
            j.beginArray();int count=0;long insertNanos=0;while(j.hasNext()){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
                String id="",name="",category="Other",extension="mp4",image="";JSONObject summary=new JSONObject();j.beginObject();
                while(j.hasNext()){
                    String key=j.nextName();
                    switch(key){
                        case "stream_id":case "series_id":id=scalar(j);break;
                        case "name":name=scalar(j);break;
                        case "category_id":category=scalar(j);break;
                        case "container_extension":extension=scalar(j);break;
                        case "stream_icon":case "cover":image=scalar(j);break;// film poster / series cover
                        case "plot":case "cast":case "director":case "genre":case "releaseDate":case "releasedate":case "release_date":case "year":case "rating":case "rating_5based":case "episode_run_time":
                            String value=scalar(j);if(value.length()>8000)value=value.substring(0,8000);summary.put(key,value);break;
                        default:j.skipValue();
                    }
                }j.endObject();if(!numericId(id))continue;if(category.isEmpty())category="Other";if(extension.isEmpty())extension="mp4";
                int column=1;if(!staging)row.bindString(column++,kind);row.bindString(column++,id);row.bindString(column++,name.isEmpty()?(kind.equals("movie")?"Film ":"Series ")+id:name);row.bindString(column++,category);row.bindString(column++,extension);row.bindString(column++,summary.toString());row.bindLong(column++,count++);row.bindString(column,Posters.clean(image));long insertStart=System.nanoTime();row.executeInsert();insertNanos+=System.nanoTime()-insertStart;
                if(count%250==0)progress.accept(count);if(count>200000)throw new IOException("Library exceeds 200,000 titles.");
            }j.endArray();android.util.Log.i("ClaireTV","library_insert kind="+kind+" titles="+count+" sqlite_ms="+(insertNanos/1000000));return count;
        }
    }
    static LibraryData.Detail movie(Catalog catalog,Source source,LibraryItem item) throws Exception{
        String json=catalog.mediaCache("movie",item.id);
        if(json==null){json=Provider.text(source.api("get_vod_info")+"&vod_id="+Source.enc(item.id),4*1024*1024);LibraryData.movie(json,item.extension);if(!Thread.currentThread().isInterrupted())catalog.putMediaCache("movie",item.id,json);}
        return LibraryData.movie(json,item.extension);
    }
    static LibraryData.Series series(Catalog catalog,Source source,LibraryItem item) throws Exception{
        String json=catalog.mediaCache("series",item.id);
        if(json==null){json=Provider.text(source.api("get_series_info")+"&series_id="+Source.enc(item.id),8*1024*1024);LibraryData.series(json);if(!Thread.currentThread().isInterrupted())catalog.putMediaCache("series",item.id,json);}
        return LibraryData.series(json);
    }
}
