package tv.local.fast;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.util.JsonReader;
import android.util.JsonToken;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

final class Provider {
    static final long REPLY_BYTES=64L*1024*1024;
    /** A whole catalog is streamed straight into SQLite and is separately bounded to 200,000 rows, so it may run far
     *  past a single reply: one real provider's series list is over 64 MB once decompressed. */
    static final long CATALOG_BYTES=384L*1024*1024;
    private static final class Limited extends FilterInputStream {
        private final long budget;
        private long remaining;
        Limited(InputStream in,long budget) { super(in);this.budget=budget;this.remaining=budget; }
        @Override public int read() throws IOException { byte[] b=new byte[1];return read(b,0,1)<0?-1:b[0]&255; }
        @Override public int read(byte[] b,int off,int len) throws IOException {
            if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            int n=in.read(b,off,len); if(n>0 && (remaining-=n)<0) throw new IOException("Provider response exceeds "+(budget/(1024*1024))+" MB."); return n;
        }
    }
    static final class Response implements AutoCloseable {
        final HttpURLConnection connection;
        final Reader reader;
        Response(String address) throws IOException {this(address,REPLY_BYTES);}
        Response(String address,long budget) throws IOException {
            connection=connect(address);
            try {reader=new InputStreamReader(body(connection,budget),StandardCharsets.UTF_8);}
            catch(IOException e) {connection.disconnect();throw e;}
        }
        @Override public void close() throws IOException {try {reader.close();} finally {connection.disconnect();}}
    }
    private static HttpURLConnection connect(String address) throws IOException {
        HttpURLConnection connection=(HttpURLConnection)new URL(Source.normalize(address)).openConnection();
        connection.setConnectTimeout(10000);connection.setReadTimeout(15000);
        connection.setRequestProperty("User-Agent","FastTV/0.1");connection.setRequestProperty("Accept-Encoding","gzip");
        connection.setInstanceFollowRedirects(false);
        return connection;
    }
    private static InputStream body(HttpURLConnection connection,long budget) throws IOException {
        int code=connection.getResponseCode();
        if(code!=200) throw new IOException("Provider returned HTTP "+code+". Check the server address and account.");
        InputStream input=connection.getInputStream();
        if("gzip".equalsIgnoreCase(connection.getContentEncoding())) input=new GZIPInputStream(input);
        return new Limited(input,budget);
    }
    static String text(String address,int maximum) throws IOException {
        try(Response r=new Response(address)) {
            StringBuilder s=new StringBuilder();char[] b=new char[4096];int n;
            while((n=r.reader.read(b))!=-1) {s.append(b,0,n);if(s.length()>maximum) throw new IOException("Guide response is too large.");}
            return s.toString();
        }
    }
    /** Bounded binary fetch, used for posters. Shares the same timeouts, gzip handling and byte budget. */
    static byte[] bytes(String address,long max) throws IOException {
        HttpURLConnection connection=connect(address);
        try(InputStream input=body(connection,max)) {
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))>0) out.write(buffer,0,n);
            if(out.size()==0) throw new IOException("Empty response.");
            return out.toByteArray();
        } finally {connection.disconnect();}
    }
    static int refresh(Catalog catalog, Source source, boolean replacing) throws Exception {
        String encrypted=Vault.encrypt(source);
        SQLiteDatabase d=catalog.getWritableDatabase();
        d.beginTransaction();
        try {
            d.delete("channels",null,null);d.delete("groups",null,null);d.delete("epg",null,null);
            if(replacing){d.delete("favorites",null,null);d.delete("category_order",null,null);Catalog.clearLibraries(d);}
            if(source.type.equals("xtream")) importGroups(d,source);
            int[] count={0};
            try(SQLiteStatement insert=d.compileStatement("INSERT OR REPLACE INTO channels(id,name,category,url,position) VALUES(?,?,?,?,?)")) {
                Playlist.Sink sink=c->{insert.clearBindings();insert.bindString(1,c.id);insert.bindString(2,c.name);insert.bindString(3,c.category);insert.bindString(4,c.url);insert.bindLong(5,count[0]++);insert.executeInsert();};
                if(source.type.equals("m3u")) {try(Response r=new Response(source.endpoint,CATALOG_BYTES)){Playlist.parse(r.reader,source.endpoint,sink);}}
                else importXtream(source,sink);
            }
            if(count[0]==0) throw new IOException("No live channels were returned. Check your source and subscription.");
            ContentValues v=new ContentValues();v.put("id","source");v.put("value",encrypted);
            d.insertWithOnConflict("meta",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            d.execSQL("DELETE FROM favorites WHERE id NOT IN (SELECT id FROM channels)");
            d.setTransactionSuccessful();return count[0];
        } finally {d.endTransaction();}
    }
    private static String scalar(JsonReader j) throws IOException {if(j.peek()==JsonToken.NULL){j.nextNull();return "";}return j.nextString();}
    static void importGroups(SQLiteDatabase d,Source s) throws Exception {
        try(Response r=new Response(s.api("get_live_categories"));JsonReader j=new JsonReader(r.reader)) {
            if(j.peek()!=JsonToken.BEGIN_ARRAY) throw new IOException("Provider did not return categories. Check your account.");
            j.beginArray();while(j.hasNext()) {
                String id="",name="";j.beginObject();while(j.hasNext()) {String key=j.nextName();if(key.equals("category_id"))id=scalar(j);else if(key.equals("category_name"))name=scalar(j);else j.skipValue();}j.endObject();
                ContentValues v=new ContentValues();v.put("id",id);v.put("name",name);d.insertWithOnConflict("groups",null,v,SQLiteDatabase.CONFLICT_REPLACE);
            }j.endArray();
        }
    }
    static void importXtream(Source source,Playlist.Sink sink) throws Exception {
        try(Response r=new Response(source.api("get_live_streams"),CATALOG_BYTES);JsonReader j=new JsonReader(r.reader)) {
            if(j.peek()!=JsonToken.BEGIN_ARRAY) throw new IOException("Provider did not return channels. Check your account.");
            j.beginArray();int count=0;
            while(j.hasNext()) {
                if(Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                String id="",name="",category="Other";
                j.beginObject();while(j.hasNext()) {
                    switch(j.nextName()) {case "stream_id":id=scalar(j);break;case "name":name=scalar(j);break;case "category_id":category=scalar(j);break;default:j.skipValue();}
                }j.endObject();
                if(!id.matches("[0-9]+"))continue;
                sink.add(new Channel(id,name.isEmpty()?"Channel "+id:name,category,"",false));
                if(++count>200000)throw new IOException("Catalog exceeds 200,000 channels.");
            }j.endArray();
        }
    }
    static String guide(Catalog catalog,Source source,Channel c) throws Exception {
        if(source.type.equals("m3u")) return "This M3U source has no guide connection. Channel browsing and playback are available; XMLTV import is not included in this version.";
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest((source.endpoint+"\n"+source.user+"\n"+c.id).getBytes(StandardCharsets.UTF_8));
        String key=java.util.Base64.getEncoder().encodeToString(hash);
        String cached=catalog.cachedEpg(key);if(cached!=null)return cached;
        JSONObject object=new JSONObject(text(source.api("get_short_epg")+"&stream_id="+Source.enc(c.id)+"&limit=6",1024*1024));
        String result=Guide.format(object);
        if(!Thread.currentThread().isInterrupted())catalog.putEpg(key,result);return result;
    }
}
