package tv.local.fast;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

final class Catalog extends SQLiteOpenHelper {
    static final int PAGE=250;
    record Group(String id,String name,int count) { @Override public String toString(){return name+"  ·  "+count;} }
    Catalog(Context c) { this(c,"catalog.db"); }
    Catalog(Context c,String name) { super(c,name,null,6); setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE channels(id TEXT PRIMARY KEY,name TEXT NOT NULL,category TEXT NOT NULL,url TEXT NOT NULL,position INTEGER NOT NULL)");
        d.execSQL("CREATE INDEX channels_category ON channels(category,position)");
        d.execSQL("CREATE INDEX channels_position ON channels(position)");
        d.execSQL("CREATE TABLE groups(id TEXT PRIMARY KEY,name TEXT NOT NULL)");
        d.execSQL("CREATE TABLE favorites(id TEXT PRIMARY KEY)");
        d.execSQL("CREATE TABLE epg(id TEXT PRIMARY KEY,body TEXT NOT NULL,updated INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE meta(id TEXT PRIMARY KEY,value TEXT NOT NULL)");
        createOrderTable(d);
        createLibraryTables(d);
    }
    private static void createOrderTable(SQLiteDatabase d) {d.execSQL("CREATE TABLE IF NOT EXISTS category_order(id TEXT PRIMARY KEY,rank INTEGER NOT NULL)");}
    @Override public void onUpgrade(SQLiteDatabase d,int old,int next) {
        if(old<2)createOrderTable(d);
        if(old<3)createLibraryTables(d);
        if(old<4){
            // v3 built media_items without a poster column; a v1/v2 upgrade just created it with one.
            if(old>=3)d.execSQL("ALTER TABLE media_items ADD COLUMN image TEXT NOT NULL DEFAULT ''");
            d.delete("media_status",null,null);// forces one re-import so saved titles gain their posters
        }
    }
    private static void createLibraryTables(SQLiteDatabase d){
        d.execSQL("CREATE TABLE IF NOT EXISTS media_items(kind TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,category TEXT NOT NULL,extension TEXT NOT NULL,summary TEXT NOT NULL,position INTEGER NOT NULL,image TEXT NOT NULL DEFAULT '',PRIMARY KEY(kind,id))");
        d.execSQL("CREATE INDEX IF NOT EXISTS media_category ON media_items(kind,category,position)");
        d.execSQL("CREATE INDEX IF NOT EXISTS media_position ON media_items(kind,position)");
        d.execSQL("CREATE TABLE IF NOT EXISTS media_groups(kind TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(kind,id))");
        d.execSQL("CREATE TABLE IF NOT EXISTS media_favorites(kind TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(kind,id))");
        d.execSQL("CREATE TABLE IF NOT EXISTS media_order(kind TEXT NOT NULL,id TEXT NOT NULL,rank INTEGER NOT NULL,PRIMARY KEY(kind,id))");
        d.execSQL("CREATE TABLE IF NOT EXISTS media_cache(kind TEXT NOT NULL,id TEXT NOT NULL,json TEXT NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(kind,id))");
        d.execSQL("CREATE TABLE IF NOT EXISTS media_status(kind TEXT PRIMARY KEY,updated INTEGER NOT NULL)");
        d.execSQL("CREATE TABLE IF NOT EXISTS playback_progress(id TEXT PRIMARY KEY,position INTEGER NOT NULL,duration INTEGER NOT NULL,updated INTEGER NOT NULL)");
    }
    static void clearLibraries(SQLiteDatabase d){for(String table:new String[]{"media_items","media_groups","media_favorites","media_order","media_cache","media_status","playback_progress"})d.delete(table,null,null);}
    Source source() throws Exception {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT value FROM meta WHERE id='source'",null)) { return c.moveToFirst()?Vault.decrypt(c.getString(0)):null; }
    }
    int count() { try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM channels",null)) { c.moveToFirst(); return c.getInt(0); } }
    List<Group> groups() {
        List<Group> list=new ArrayList<>();
        list.add(new Group("", "All channels",count()));
        try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM favorites JOIN channels USING(id)",null)) { c.moveToFirst(); list.add(new Group("*","Favorites",c.getInt(0))); }
        // A category the provider never named falls back to its bare id, which would otherwise sort above every real name.
        try(Cursor c=getReadableDatabase().rawQuery("SELECT c.category,coalesce(g.name,'Category '||c.category),count(*) FROM channels c LEFT JOIN groups g ON c.category=g.id GROUP BY c.category ORDER BY (g.name IS NULL),2 COLLATE NOCASE",null)) {
            while(c.moveToNext()) list.add(new Group(c.getString(0),c.getString(1),c.getInt(2)));
        }
        java.util.Map<String,Integer> ranks=new java.util.HashMap<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,rank FROM category_order",null)){while(c.moveToNext())ranks.put(c.getString(0),c.getInt(1));}
        list.sort(java.util.Comparator.comparingInt(g->ranks.getOrDefault(g.id(),Integer.MAX_VALUE)));
        return list;
    }
    void saveGroupOrder(List<Group> groups) {
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try(android.database.sqlite.SQLiteStatement row=d.compileStatement("INSERT OR REPLACE INTO category_order(id,rank) VALUES(?,?)")){
            d.delete("category_order",null,null);
            for(int i=0;i<groups.size();i++){row.bindString(1,groups.get(i).id());row.bindLong(2,i);row.executeInsert();}
            d.setTransactionSuccessful();
        }finally{d.endTransaction();}
    }
    /** Virtual category ids: "" every title, "*" favorites, "~" the ones with somewhere to resume from. */
    static final String CONTINUE="~";
    private record Filter(String where,String[] args) {}
    private static String like(String search){return "%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";}
    private Filter filter(String category,String search) {
        List<String> args=new ArrayList<>();String where="1=1";
        if(category.equals("*"))where+=" AND f.id IS NOT NULL";
        else if(!category.isEmpty()){where+=" AND c.category=?";args.add(category);}
        if(!search.isEmpty()){where+=" AND c.name LIKE ? ESCAPE '\\'";args.add(like(search));}
        return new Filter(where,args.toArray(new String[0]));
    }
    int matchCount(String category,String search) {
        Filter f=filter(category,search);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM channels c LEFT JOIN favorites f ON c.id=f.id WHERE "+f.where,f.args)){c.moveToFirst();return c.getInt(0);}
    }
    List<Channel> page(String category,String search,int offset) {
        Filter f=filter(category,search);
        List<Channel> list=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT c.id,c.name,c.category,c.url,f.id IS NOT NULL FROM channels c LEFT JOIN favorites f ON c.id=f.id WHERE "+f.where+" ORDER BY c.position LIMIT "+(PAGE+1)+" OFFSET "+offset,f.args)) {
            while(c.moveToNext()) list.add(new Channel(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4)!=0));
        }
        return list;
    }
    Channel neighbor(String id,String category,String search,int direction) {
        Filter f=filter(category,search);boolean forwards=direction>0;
        String projection="SELECT c.id,c.name,c.category,c.url,f.id IS NOT NULL FROM channels c LEFT JOIN favorites f ON c.id=f.id WHERE "+f.where;
        String order=" ORDER BY c.position "+(forwards?"ASC":"DESC")+" LIMIT 1";
        String[] args=java.util.Arrays.copyOf(f.args,f.args.length+1);args[args.length-1]=id;
        String next=projection+" AND c.position "+(forwards?">":"<")+" (SELECT position FROM channels WHERE id=?)"+order;
        try(Cursor c=getReadableDatabase().rawQuery(next,args)){if(c.moveToFirst())return readChannel(c);}
        try(Cursor c=getReadableDatabase().rawQuery(projection+order,f.args)){return c.moveToFirst()?readChannel(c):null;}
    }
    private static Channel readChannel(Cursor c){return new Channel(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getInt(4)!=0);}
    void favorite(Channel channel) {
        SQLiteDatabase d=getWritableDatabase();
        if(channel.favorite) d.delete("favorites","id=?",new String[]{channel.id});
        else { ContentValues v=new ContentValues(); v.put("id",channel.id); d.insertWithOnConflict("favorites",null,v,SQLiteDatabase.CONFLICT_IGNORE); }
    }
    String cachedEpg(String id) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT body FROM epg WHERE id=? AND updated>?",new String[]{id,Long.toString(System.currentTimeMillis()-15*60*1000)})) {
            return c.moveToFirst()?c.getString(0):null;
        }
    }
    void putEpg(String id,String body) {
        ContentValues v=new ContentValues();v.put("id",id);v.put("body",body);v.put("updated",System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict("epg",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        getWritableDatabase().execSQL("DELETE FROM epg WHERE updated<?",new Object[]{System.currentTimeMillis()-24*60*60*1000});
    }
    boolean mediaImported(String kind){try(Cursor c=getReadableDatabase().rawQuery("SELECT 1 FROM media_status WHERE kind=?",new String[]{kind})){return c.moveToFirst();}}
    /**
     * A film is resumable by its own key; a series is resumable when any of its episodes is, so the two
     * libraries need different progress keys. Titles watched to the end are already stored at position 0.
     */
    private static String resumable(String kind){
        return kind.equals("series")
            ?"EXISTS(SELECT 1 FROM playback_progress p WHERE p.position>0 AND p.id LIKE 'episode:'||c.id||':%')"
            :"EXISTS(SELECT 1 FROM playback_progress p WHERE p.position>0 AND p.id='movie:'||c.id)";
    }
    private static String resumedAt(String kind){
        return "(SELECT max(p.updated) FROM playback_progress p WHERE p.position>0 AND "
            +(kind.equals("series")?"p.id LIKE 'episode:'||c.id||':%'":"p.id='movie:'||c.id")+")";
    }
    private Filter mediaFilter(String kind,String category,String search){
        if(category.equals(CONTINUE)){
            List<String> args=new ArrayList<>();args.add(kind);
            String where="c.kind=? AND "+resumable(kind);
            if(!search.isEmpty()){where+=" AND c.name LIKE ? ESCAPE '\\'";args.add(like(search));}
            return new Filter(where,args.toArray(new String[0]));
        }
        Filter f=filter(category,search);String[] args=new String[f.args.length+1];args[0]=kind;System.arraycopy(f.args,0,args,1,f.args.length);
        return new Filter("c.kind=? AND "+f.where,args);
    }
    int mediaCount(String kind,String category,String search){
        Filter f=mediaFilter(kind,category,search);
        try(Cursor c=getReadableDatabase().rawQuery("SELECT count(*) FROM media_items c LEFT JOIN media_favorites f ON c.kind=f.kind AND c.id=f.id WHERE "+f.where,f.args)){c.moveToFirst();return c.getInt(0);}
    }
    List<Group> mediaGroups(String kind){
        List<Group> list=new ArrayList<>();list.add(new Group("",kind.equals("movie")?"All films":"All series",mediaCount(kind,"","")));
        list.add(new Group(CONTINUE,"Continue watching",mediaCount(kind,CONTINUE,"")));
        list.add(new Group("*","Favorites",mediaCount(kind,"*","")));
        try(Cursor c=getReadableDatabase().rawQuery("SELECT c.category,coalesce(g.name,'Category '||c.category),count(*) FROM media_items c LEFT JOIN media_groups g ON c.kind=g.kind AND c.category=g.id WHERE c.kind=? GROUP BY c.category ORDER BY (g.name IS NULL),2 COLLATE NOCASE",new String[]{kind})){
            while(c.moveToNext())list.add(new Group(c.getString(0),c.getString(1),c.getInt(2)));
        }
        java.util.Map<String,Integer> order=new java.util.HashMap<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,rank FROM media_order WHERE kind=?",new String[]{kind})){while(c.moveToNext())order.put(c.getString(0),c.getInt(1));}
        list.sort(java.util.Comparator.comparingInt(g->order.getOrDefault(g.id(),Integer.MAX_VALUE)));return list;
    }
    List<LibraryItem> mediaPage(String kind,String category,String search,int offset){
        Filter f=mediaFilter(kind,category,search);List<LibraryItem> list=new ArrayList<>();
        // Continue watching reads newest-first; every other category keeps the provider's own order.
        String order=category.equals(CONTINUE)?resumedAt(kind)+" DESC":"c.position";
        try(Cursor c=getReadableDatabase().rawQuery("SELECT c.id,c.name,c.category,c.extension,c.summary,c.image,f.id IS NOT NULL FROM media_items c LEFT JOIN media_favorites f ON c.kind=f.kind AND c.id=f.id WHERE "+f.where+" ORDER BY "+order+" LIMIT "+PAGE+" OFFSET "+offset,f.args)){
            while(c.moveToNext())list.add(new LibraryItem(kind,c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getInt(6)!=0));
        }return list;
    }
    void mediaFavorite(LibraryItem item){
        SQLiteDatabase d=getWritableDatabase();if(item.favorite)d.delete("media_favorites","kind=? AND id=?",new String[]{item.kind,item.id});
        else{ContentValues v=new ContentValues();v.put("kind",item.kind);v.put("id",item.id);d.insertWithOnConflict("media_favorites",null,v,SQLiteDatabase.CONFLICT_IGNORE);}
    }
    void saveMediaOrder(String kind,List<Group> groups){
        SQLiteDatabase d=getWritableDatabase();d.beginTransaction();
        try(android.database.sqlite.SQLiteStatement row=d.compileStatement("INSERT OR REPLACE INTO media_order(kind,id,rank) VALUES(?,?,?)")){
            d.delete("media_order","kind=?",new String[]{kind});
            for(int i=0;i<groups.size();i++){row.bindString(1,kind);row.bindString(2,groups.get(i).id());row.bindLong(3,i);row.executeInsert();}d.setTransactionSuccessful();
        }finally{d.endTransaction();}
    }
    String mediaCache(String kind,String id){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT json FROM media_cache WHERE kind=? AND id=? AND updated>?",new String[]{kind,id,Long.toString(System.currentTimeMillis()-6*60*60*1000)})){return c.moveToFirst()?c.getString(0):null;}
    }
    void putMediaCache(String kind,String id,String json){
        ContentValues v=new ContentValues();v.put("kind",kind);v.put("id",id);v.put("json",json);v.put("updated",System.currentTimeMillis());getWritableDatabase().insertWithOnConflict("media_cache",null,v,SQLiteDatabase.CONFLICT_REPLACE);
        getWritableDatabase().execSQL("DELETE FROM media_cache WHERE rowid NOT IN (SELECT rowid FROM media_cache ORDER BY updated DESC,rowid DESC LIMIT 512)");
    }
    long playbackPosition(String id){try(Cursor c=getReadableDatabase().rawQuery("SELECT position FROM playback_progress WHERE id=?",new String[]{id})){return c.moveToFirst()?c.getLong(0):0;}}
    /** The episode of this series watched most recently and not finished, so its page opens where it stopped. */
    String resumeEpisode(String seriesId){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id FROM playback_progress WHERE position>0 AND id LIKE 'episode:'||?||':%' ORDER BY updated DESC LIMIT 1",new String[]{seriesId})){
            if(!c.moveToFirst())return "";
            String key=c.getString(0);int at=key.lastIndexOf(':');return at<0?"":key.substring(at+1);
        }
    }
    void savePlayback(String id,long position,long duration){
        if(id==null||position<0)return;
        if(duration>0&&duration-position<60000)position=0;
        ContentValues v=new ContentValues();v.put("id",id);v.put("position",position);v.put("duration",Math.max(0,duration));v.put("updated",System.currentTimeMillis());getWritableDatabase().insertWithOnConflict("playback_progress",null,v,SQLiteDatabase.CONFLICT_REPLACE);
    }
}
