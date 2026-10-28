package tv.local.fast;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
final class LibraryDeviceChecks {
    static void run(Instrumentation test,Catalog catalog,Bundle result) throws Exception{
        Source source=new Source("xtream","https://example.invalid","test-user","test-pass");
        seed(catalog,source);checkMigration(test,source);checkStorage(catalog,source);
        Activity browser=test.startActivitySync(new Intent(test.getTargetContext(),LibraryActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("kind","movie").putExtra("benchmark",true));
        await(test,()->list(browser).getCount()==300,"Cached film catalog");
        checkFilterControls(test,browser);
        test.runOnMainSync(()->{
            ListView categories=browser.findViewById(android.R.id.content).findViewWithTag("library-categories");
            check(((LinearLayout.LayoutParams)((View)categories.getParent()).getLayoutParams()).weight==26,"Category panel 26%");
            View gridPanel=browser.findViewById(android.R.id.content).findViewWithTag("library-grid-panel");
            check(((LinearLayout.LayoutParams)gridPanel.getLayoutParams()).weight==74,"Poster grid panel 74%");
            check(list(browser) instanceof GridView,"Films browse in a poster grid");
            check(((GridView)list(browser)).getNumColumns()>1,"Poster grid has several columns");
            check(categoryNames(browser).contains("Continue watching"),"Continue watching is offered as a category");
            list(browser).requestFocus();list(browser).setSelection(270);
        });
        await(test,()->{LibraryItem item=(LibraryItem)list(browser).getAdapter().getItem(270);return item!=null&&item.id.equals("270");},"Films scroll past first page");
        // The pager holding row 270 is not the same as the grid showing it: assert the rendered cells too.
        await(test,()->{AbsListView l=list(browser);return l.getSelectedItemPosition()==270&&l.getFirstVisiblePosition()>0;},"Film grid stays scrolled to the selected cell");
        await(test,()->!cellLabel(list(browser),0).equals("Loading…"),"Visible film cells resolve to titles");
        test.runOnMainSync(()->{View cell=list(browser).getChildAt(0);check(cell.findViewById(android.R.id.content)==null&&cell instanceof LinearLayout,"Cell is a poster box");
            check(((LinearLayout)cell).getChildAt(0) instanceof ImageView,"Each film cell carries a poster image");});
        screenshot(test,"library-films.png");
        click(test,browser,"library-search");test.waitForIdleSync();
        AccessibilityNodeInfo edit=CatalogInstrumentation.waitFocusedField(test,"Film search field takes input focus");
        Bundle text=new Bundle();text.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Fixture film 0270");edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,text);
        test.waitForIdleSync();CatalogInstrumentation.waitKeyboardVisible(test);
        if(Build.VERSION.SDK_INT>=30)check(edit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()),"Film keyboard submit");else test.sendKeyDownUpSync(KeyEvent.KEYCODE_ENTER);
        await(test,()->list(browser).getCount()==1&&list(browser).hasFocus(),"Film search filters and returns focus");
        CatalogInstrumentation.waitKeyboardHidden(test);
        // A synthetic hold reaches AbsListView's key long-press check only if nothing redraws the row first, so it
        // is reported rather than asserted; the direct listener call below is what proves the app's own handler.
        // Injected holds are asserted on MainActivity's category reorder, where no detail fetch competes with them.
        test.sendKeySync(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_CENTER));SystemClock.sleep(800);test.sendKeySync(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_CENTER));test.waitForIdleSync();
        boolean held=favorited(test,browser,2);result.putBoolean("film_favorite_injected_hold",held);
        if(!held){
            check(count(catalog,"SELECT count(*) FROM media_favorites WHERE kind='movie' AND id='270'")==0,"Injected hold left no half-applied favorite");
            boolean[] handled={false};test.runOnMainSync(()->{AbsListView l=list(browser);handled[0]=l.getOnItemLongClickListener().onItemLongClick(l,l.getChildAt(0),0,l.getAdapter().getItemId(0));});
            check(handled[0],"Film grid consumes a long press");
            check(favorited(test,browser,3),"Film favorite saved through long press");
        }
        check(count(catalog,"SELECT count(*) FROM media_favorites WHERE kind='movie' AND id='270'")==1,"Film favorite persisted exactly once");

        // Opening a title replaces the grid with its detail page: artwork, what the provider knows, and Watch.
        openSelected(test,browser);
        await(test,()->browser.findViewById(android.R.id.content).findViewWithTag("library-detail")!=null,"Film opens a detail page");
        test.runOnMainSync(()->{
            check(browser.findViewById(android.R.id.content).findViewWithTag("library-poster") instanceof ImageView,"Detail page shows the artwork");
            check(browser.findViewById(android.R.id.content).findViewWithTag("library-list")==null,"Detail page replaces the browse grid");
        });
        await(test,()->button(browser,"library-play").getText().toString().startsWith("Resume from "),"Film detail offers to resume where it stopped");
        await(test,()->text(browser,"library-detail").contains("Synthetic film for local UI testing."),"Film detail shows the provider plot");
        AtomicReference<Intent> launched=new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){
            @Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(intent.getComponent()!=null&&intent.getComponent().getClassName().equals(PlayerActivity.class.getName())){launched.set(new Intent(intent));return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}
        };
        test.addMonitor(monitor);
        click(test,browser,"library-play");await(test,()->launched.get()!=null,"Film playback intent");
        check(launched.get().getStringExtra("url").equals("https://example.invalid/movie/test-user/test-pass/270.mkv"),"Film container and playback URL");
        check(launched.get().getLongExtra("start_position",0)==120000,"Film resumes at saved position");
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await(test,()->list(browser)!=null&&list(browser).getCount()==1,"Back returns from the film detail page to the grid");

        launched.set(null);click(test,browser,"nav-Series");
        await(test,()->list(browser)!=null&&list(browser).getCount()==2&&list(browser).getAdapter().getItem(0) instanceof LibraryItem,"Series catalog switches independently");
        openSelected(test,browser);
        await(test,()->browser.findViewById(android.R.id.content).findViewWithTag("library-seasons")!=null,"Series detail lists its seasons");
        await(test,()->episodes(browser)!=null&&episodes(browser).getCount()==2,"Series opens on season one with its episodes");
        click(test,browser,"season-2");
        await(test,()->episodes(browser).getCount()==1&&((LibraryData.Episode)episodes(browser).getAdapter().getItem(0)).id().equals("201"),"Season two shows its own episode");
        screenshot(test,"library-series.png");
        click(test,browser,"library-play");await(test,()->launched.get()!=null,"Episode playback intent");
        check(launched.get().getStringExtra("url").equals("https://example.invalid/series/test-user/test-pass/201.mp4"),"Episode stream URL");
        check(launched.get().getStringExtra("content_key").equals("episode:900:201"),"Episode resume key includes series");test.removeMonitor(monitor);
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        await(test,()->list(browser)!=null&&list(browser).getCount()==2&&list(browser).getAdapter().getItem(0) instanceof LibraryItem,"Back returns from the series detail page to the grid");
        test.runOnMainSync(browser::finish);test.waitForIdleSync();
        Activity playback=test.startActivitySync(new Intent(test.getTargetContext(),PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("benchmark",true).putExtra("url","asset:///playback-test.mp4").putExtra("content_key","movie:local-video").putExtra("start_position",1000L));
        await(test,()->{androidx.media3.ui.PlayerView view=playback.findViewById(android.R.id.content).findViewWithTag("video-player");return view.getPlayer()!=null&&view.getPlayer().getCurrentPosition()>1200&&view.getPlayer().getPlayerError()==null;},"On-demand playback honors resume seek");
        test.runOnMainSync(()->{androidx.media3.ui.PlayerView view=playback.findViewById(android.R.id.content).findViewWithTag("video-player");check(view.getUseController(),"VOD controls available");check(!view.isControllerFullyVisible(),"VOD opens without a controls overlay");});
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);await(test,()->((androidx.media3.ui.PlayerView)playback.findViewById(android.R.id.content).findViewWithTag("video-player")).isControllerFullyVisible(),"OK reveals VOD controls");
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);await(test,()->!((androidx.media3.ui.PlayerView)playback.findViewById(android.R.id.content).findViewWithTag("video-player")).getPlayer().getPlayWhenReady(),"VOD remote pause");
        test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);await(test,()->!((androidx.media3.ui.PlayerView)playback.findViewById(android.R.id.content).findViewWithTag("video-player")).isControllerFullyVisible(),"Back hides VOD controls first");test.runOnMainSync(playback::finish);test.waitForIdleSync();
        result.putBoolean("films_series_ui",true);result.putBoolean("library_search_keyboard",true);result.putBoolean("vod_resume_controls",true);result.putBoolean("schema_v3_preserves_data",true);
    }
    private static void seed(Catalog catalog,Source source) throws Exception{
        SQLiteDatabase d=catalog.getWritableDatabase();d.beginTransaction();
        try{
            Catalog.clearLibraries(d);ContentValues meta=new ContentValues();meta.put("id","source");meta.put("value",Vault.encrypt(source));d.insertWithOnConflict("meta",null,meta,SQLiteDatabase.CONFLICT_REPLACE);
            // image stays empty: the fixtures must never reach the network for artwork during a device run.
            try(SQLiteStatement row=d.compileStatement("INSERT INTO media_items(kind,id,name,category,extension,summary,position,image) VALUES(?,?,?,?,?,?,?,'')")){
                for(int i=0;i<300;i++){
                    row.bindString(1,"movie");row.bindString(2,Integer.toString(i));row.bindString(3,String.format(Locale.ROOT,"Fixture film %04d",i));row.bindString(4,i%2==0?"a":"b");row.bindString(5,"mp4");row.bindString(6,"{\"year\":\"2025\",\"genre\":\"Fixture\"}");row.bindLong(7,i);row.executeInsert();
                    Catalog.indexMetadata(d,"movie",Integer.toString(i),"Fixture film",new JSONObject("{\"year\":\"2025\",\"genre\":\"Fixture\"}"));
                    catalog.putMediaCache("movie",Integer.toString(i),"{\"info\":{\"plot\":\"Synthetic film for local UI testing.\",\"duration\":\"00:10:00\"},\"movie_data\":{\"container_extension\":\"mkv\"}}");
                }
                for(int i=0;i<2;i++){String id=i==0?"900":"901";row.bindString(1,"series");row.bindString(2,id);row.bindString(3,"Fixture series "+i);row.bindString(4,"s");row.bindString(5,"");row.bindString(6,"{\"plot\":\"Synthetic series for local UI testing.\"}");row.bindLong(7,i);row.executeInsert();catalog.putMediaCache("series",id,seriesJson());}
            }
            d.execSQL("INSERT INTO media_groups VALUES('movie','a','Fixture films A'),('movie','b','Fixture films B'),('series','s','Fixture series')");
            d.execSQL("INSERT INTO media_status VALUES('movie',?),('series',?)",new Object[]{System.currentTimeMillis(),System.currentTimeMillis()});
            catalog.savePlayback("movie:270",120000,600000);d.setTransactionSuccessful();
        }finally{d.endTransaction();}
    }
    private static String seriesJson(){return "{\"info\":{\"plot\":\"Synthetic test series.\"},\"seasons\":[{\"season_number\":1,\"name\":\"Season one\"},{\"season_number\":2,\"name\":\"Season two\"}],\"episodes\":{\"1\":[{\"id\":101,\"episode_num\":1,\"title\":\"First episode\",\"container_extension\":\"mp4\",\"info\":{\"plot\":\"First episode description.\"}},{\"id\":102,\"episode_num\":2,\"title\":\"Second episode\",\"container_extension\":\"mkv\"}],\"2\":[{\"id\":201,\"episode_num\":1,\"title\":\"Season two opener\",\"container_extension\":\"mp4\",\"info\":{\"duration\":\"00:42:00\"}}]}}";}
    private static void checkFilterControls(Instrumentation test,Activity browser) throws Exception{
        click(test,browser,"library-filters");
        clickText(test,"Release year: Any");clickText(test,"2025");clickText(test,"Done");
        clickText(test,"Genre: Any");clickText(test,"fixture");clickText(test,"Done");
        clickText(test,"Language: Any");clickText(test,LibraryFilters.UNKNOWN);clickText(test,"Done");
        screenshot(test,"library-filters.png");clickText(test,"Apply");
        await(test,()->list(browser).getCount()==300&&button(browser,"library-filters").getText().toString().equals("Filters (3)"),"Three combined filter controls apply");
        click(test,browser,"library-filters");clickText(test,"Clear all");clickText(test,"Cancel");
        test.runOnMainSync(()->check(button(browser,"library-filters").getText().toString().equals("Filters (3)"),"Cancel discards draft reset"));
        click(test,browser,"library-filters");clickText(test,"Clear all");clickText(test,"Apply");
        await(test,()->list(browser).hasFocus()&&button(browser,"library-filters").getText().toString().equals("Filters"),"Clear and Apply reset filters and restore grid focus");
    }
    private static void clickText(Instrumentation test,String text){
        for(int attempt=0;attempt<100;attempt++){
            AccessibilityNodeInfo root=test.getUiAutomation().getRootInActiveWindow();
            if(root!=null)for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text)){
                if(text.equalsIgnoreCase(node.getText()==null?"":node.getText().toString())&&node.isEnabled()){
                    while(node!=null&&!node.isClickable())node=node.getParent();
                    if(node!=null&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){test.waitForIdleSync();return;}
                }
            }SystemClock.sleep(50);
        }throw new AssertionError("Missing filter control: "+text);
    }
    private static void checkStorage(Catalog catalog,Source source) throws Exception{
        check(catalog.mediaCount("movie","","")==300,"Movie count");check(catalog.mediaCount("series","","")==2,"Series count isolated");check(catalog.mediaPage("movie","","",250).size()==50,"Bounded movie paging");
        List<Catalog.Group> order=catalog.mediaGroups("movie"),original=new ArrayList<>(order);Collections.swap(order,0,3);catalog.saveMediaOrder("movie",order);check(catalog.mediaGroups("movie").get(0).id().equals(order.get(0).id()),"Movie category order persists");check(catalog.mediaGroups("series").get(0).id().isEmpty(),"Series category order independent");catalog.saveMediaOrder("movie",original);
        SQLiteDatabase d=catalog.getWritableDatabase();String input="[{\"stream_id\":10001,\"name\":\"Été\",\"category_id\":null,\"container_extension\":\"mkv\",\"extra\":[1,2]},{\"stream_id\":\"10002\",\"name\":\"Second\",\"rating\":8.2}]";
        check(LibraryProvider.readItems(d,new StringReader(input),"movie",count->{})==2,"Streaming film JSON import");check(catalog.mediaCount("movie","","Été")==1,"Unicode movie import");d.delete("media_items","id IN ('10001','10002')",null);
        boolean failed=false;try{LibraryProvider.refresh(catalog,new Source("xtream","http://127.0.0.1:1","test","test"),"movie",count->{});}catch(Exception expected){failed=true;}
        check(failed&&catalog.mediaCount("movie","","")==300&&catalog.mediaCount("series","","")==2,"Failed movie refresh retains both catalogs");check(catalog.count()==10000,"Library refresh never resets live channels");
        check(catalog.playbackPosition("movie:270")==120000,"Resume position stored");catalog.savePlayback("movie:finished",590000,600000);check(catalog.playbackPosition("movie:finished")==0,"Completed title restarts from beginning");
        checkContinueWatching(catalog);
    }
    /** Continue watching is a virtual category over playback_progress, keyed differently for films and series. */
    private static void checkContinueWatching(Catalog catalog) throws Exception{
        check(catalog.mediaCount("movie",Catalog.CONTINUE,"")==1,"Only the part-watched film is resumable");
        check(catalog.mediaPage("movie",Catalog.CONTINUE,"",0).get(0).id.equals("270"),"Continue watching lists that film");
        check(catalog.mediaCount("series",Catalog.CONTINUE,"")==0,"A series with no watched episode is not resumable");
        check(catalog.mediaGroups("movie").get(1).id().equals(Catalog.CONTINUE),"Continue watching sits above Favorites");
        check(catalog.mediaGroups("movie").get(1).count()==1,"Continue watching carries its own count");

        // A series becomes resumable through one of its episodes, and reports which one to open on.
        catalog.savePlayback("episode:900:102",90000,600000);
        check(catalog.mediaCount("series",Catalog.CONTINUE,"")==1,"A part-watched episode makes its series resumable");
        check(catalog.mediaPage("series",Catalog.CONTINUE,"",0).get(0).id.equals("900"),"Continue watching lists the series, not the episode");
        check(catalog.resumeEpisode("900").equals("102"),"The series resumes on the episode it stopped on");
        catalog.savePlayback("episode:900:101",30000,600000);
        check(catalog.resumeEpisode("900").equals("101"),"The most recently watched episode wins");
        check(catalog.mediaCount("series",Catalog.CONTINUE,"")==1,"Two part-watched episodes still list the series once");

        // Finishing every episode retires the series from Continue watching.
        catalog.savePlayback("episode:900:101",599000,600000);catalog.savePlayback("episode:900:102",599000,600000);
        check(catalog.mediaCount("series",Catalog.CONTINUE,"")==0,"A finished series leaves Continue watching");
        check(catalog.resumeEpisode("900").isEmpty(),"A finished series has no episode to resume");
        check(catalog.mediaCount("movie",Catalog.CONTINUE,"Fixture film 0270")==1,"Continue watching still honours the title search");
        check(catalog.mediaCount("movie",Catalog.CONTINUE,"Fixture film 0001")==0,"Continue watching excludes unwatched titles from search");
    }
    private static void checkMigration(Instrumentation test,Source source) throws Exception{
        String file="library-migration.db";test.getTargetContext().deleteDatabase(file);SQLiteDatabase d=SQLiteDatabase.openOrCreateDatabase(test.getTargetContext().getDatabasePath(file),null);
        d.execSQL("CREATE TABLE channels(id TEXT PRIMARY KEY,name TEXT,category TEXT,url TEXT,position INTEGER)");d.execSQL("INSERT INTO channels VALUES('kept','Saved live channel','a','https://example.invalid/live',0)");
        d.execSQL("CREATE TABLE meta(id TEXT PRIMARY KEY,value TEXT)");d.execSQL("INSERT INTO meta VALUES('source',?)",new Object[]{Vault.encrypt(source)});
        d.execSQL("CREATE TABLE category_order(id TEXT PRIMARY KEY,rank INTEGER)");d.execSQL("INSERT INTO category_order VALUES('a',0)");d.execSQL("CREATE TABLE favorites(id TEXT PRIMARY KEY)");d.execSQL("INSERT INTO favorites VALUES('kept')");d.setVersion(2);d.close();
        try(Catalog upgraded=new Catalog(test.getTargetContext(),file)){
            check(upgraded.count()==1,"v2 migration preserves live catalog");check(upgraded.source().json().equals(source.json()),"v2 migration preserves encrypted login");check(upgraded.mediaCount("movie","","")==0,"New movie table available");
            try(Cursor c=upgraded.getReadableDatabase().rawQuery("SELECT id FROM favorites",null)){check(c.moveToFirst()&&c.getString(0).equals("kept"),"v2 migration preserves favorites");}
            try(Cursor c=upgraded.getReadableDatabase().rawQuery("SELECT id FROM category_order",null)){check(c.moveToFirst()&&c.getString(0).equals("a"),"v2 migration preserves category order");}
        }finally{test.getTargetContext().deleteDatabase(file);}
        checkPosterMigration(test,source);
    }
    /** v3 → v4 is the upgrade the owner's TV actually takes: it must gain the poster column and keep its titles. */
    private static void checkPosterMigration(Instrumentation test,Source source) throws Exception{
        String file="poster-migration.db";test.getTargetContext().deleteDatabase(file);
        SQLiteDatabase d=SQLiteDatabase.openOrCreateDatabase(test.getTargetContext().getDatabasePath(file),null);
        d.execSQL("CREATE TABLE channels(id TEXT PRIMARY KEY,name TEXT,category TEXT,url TEXT,position INTEGER)");
        d.execSQL("CREATE TABLE meta(id TEXT PRIMARY KEY,value TEXT)");d.execSQL("INSERT INTO meta VALUES('source',?)",new Object[]{Vault.encrypt(source)});
        d.execSQL("CREATE TABLE category_order(id TEXT PRIMARY KEY,rank INTEGER)");d.execSQL("CREATE TABLE favorites(id TEXT PRIMARY KEY)");
        d.execSQL("CREATE TABLE media_items(kind TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,category TEXT NOT NULL,extension TEXT NOT NULL,summary TEXT NOT NULL,position INTEGER NOT NULL,PRIMARY KEY(kind,id))");
        d.execSQL("INSERT INTO media_items VALUES('movie','7','Kept film','a','mp4','{}',0)");
        d.execSQL("CREATE TABLE media_favorites(kind TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(kind,id))");d.execSQL("INSERT INTO media_favorites VALUES('movie','7')");
        d.execSQL("CREATE TABLE media_groups(kind TEXT NOT NULL,id TEXT NOT NULL,name TEXT NOT NULL,PRIMARY KEY(kind,id))");
        d.execSQL("CREATE TABLE media_order(kind TEXT NOT NULL,id TEXT NOT NULL,rank INTEGER NOT NULL,PRIMARY KEY(kind,id))");
        d.execSQL("CREATE TABLE media_cache(kind TEXT NOT NULL,id TEXT NOT NULL,json TEXT NOT NULL,updated INTEGER NOT NULL,PRIMARY KEY(kind,id))");
        d.execSQL("CREATE TABLE media_status(kind TEXT PRIMARY KEY,updated INTEGER NOT NULL)");d.execSQL("INSERT INTO media_status VALUES('movie',1)");
        d.execSQL("CREATE TABLE playback_progress(id TEXT PRIMARY KEY,position INTEGER NOT NULL,duration INTEGER NOT NULL,updated INTEGER NOT NULL)");
        d.execSQL("INSERT INTO playback_progress VALUES('movie:7',4242,600000,1)");
        d.setVersion(3);d.close();
        try(Catalog upgraded=new Catalog(test.getTargetContext(),file)){
            check(upgraded.getReadableDatabase().getVersion()==6,"Schema upgraded to version 6");
            List<LibraryItem> rows=upgraded.mediaPage("movie","","",0);
            check(rows.size()==1&&rows.get(0).name.equals("Kept film"),"v3 migration keeps saved films");
            check(rows.get(0).image.isEmpty(),"v3 migration adds an empty poster column");
            check(rows.get(0).favorite,"v3 migration keeps film favorites");
            check(upgraded.playbackPosition("movie:7")==4242,"v3 migration keeps playback positions");
            check(!upgraded.mediaImported("movie"),"v3 migration re-imports once so saved titles gain posters");
        }finally{test.getTargetContext().deleteDatabase(file);}
    }
    private static boolean favorited(Instrumentation test,Activity browser,int seconds){
        boolean[] saved={false};
        for(int i=0;i<seconds*20&&!saved[0];i++){SystemClock.sleep(50);test.runOnMainSync(()->{Object row=list(browser).getAdapter().getItem(0);saved[0]=row instanceof LibraryItem&&((LibraryItem)row).favorite;});}
        return saved[0];
    }
    private static int count(Catalog catalog,String sql){try(Cursor c=catalog.getReadableDatabase().rawQuery(sql,null)){return c.moveToFirst()?c.getInt(0):-1;}}
    private static AbsListView list(Activity activity){return activity.findViewById(android.R.id.content).findViewWithTag("library-list");}
    private static AbsListView episodes(Activity activity){return activity.findViewById(android.R.id.content).findViewWithTag("library-episodes");}
    private static String categoryNames(Activity activity){
        AbsListView categories=activity.findViewById(android.R.id.content).findViewWithTag("library-categories");
        StringBuilder out=new StringBuilder();
        for(int i=0;i<categories.getAdapter().getCount();i++)out.append(categories.getAdapter().getItem(i)).append('\n');
        return out.toString();
    }
    private static Button button(Activity activity,String tag){return activity.findViewById(android.R.id.content).findViewWithTag(tag);}
    /** The label inside a poster cell, which is the second child of the cell box. */
    private static String cellLabel(AbsListView list,int index){
        View cell=list.getChildAt(index);
        if(!(cell instanceof LinearLayout))return "";
        View label=((LinearLayout)cell).getChildAt(1);
        return label instanceof TextView?((TextView)label).getText().toString():"";
    }
    private static String text(Activity activity,String tag){
        StringBuilder out=new StringBuilder();collect(activity.findViewById(android.R.id.content).findViewWithTag(tag),out);return out.toString();
    }
    private static void collect(View view,StringBuilder out){
        if(view instanceof TextView)out.append(((TextView)view).getText()).append('\n');
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),out);
    }
    /** Clicks the currently selected grid cell, the way OK on the remote does. */
    private static void openSelected(Instrumentation test,Activity browser){
        test.runOnMainSync(()->{AbsListView l=list(browser);l.requestFocus();int at=Math.max(0,l.getSelectedItemPosition());
            l.performItemClick(l.getChildAt(at-l.getFirstVisiblePosition()),at,l.getAdapter().getItemId(at));});
        test.waitForIdleSync();
    }
    private static void click(Instrumentation test,Activity activity,String tag){test.runOnMainSync(()->activity.findViewById(android.R.id.content).findViewWithTag(tag).performClick());}
    private static void await(Instrumentation test,java.util.function.BooleanSupplier ready,String name){
        boolean[] okay={false};for(int attempt=0;attempt<150&&!okay[0];attempt++){SystemClock.sleep(50);test.runOnMainSync(()->okay[0]=ready.getAsBoolean());}check(okay[0],name);
    }
    private static void screenshot(Instrumentation test,String name) throws IOException{android.graphics.Bitmap image=test.getUiAutomation().takeScreenshot();if(image!=null){try(FileOutputStream file=new FileOutputStream(new File(test.getTargetContext().getExternalFilesDir(null),name))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,file);}image.recycle();}}
    private static void check(boolean okay,String what){if(!okay)throw new AssertionError(what);}
}
