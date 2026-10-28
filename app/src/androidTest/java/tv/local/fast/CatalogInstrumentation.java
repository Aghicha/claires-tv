package tv.local.fast;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import java.util.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class CatalogInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart() {
        Bundle results=new Bundle();Catalog catalog=new Catalog(getTargetContext(),"benchmark.db");
        try {
            android.accessibilityservice.AccessibilityServiceInfo access=getUiAutomation().getServiceInfo();
            access.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;getUiAutomation().setServiceInfo(access);
            SQLiteDatabase db=catalog.getWritableDatabase();db.beginTransaction();long began=SystemClock.elapsedRealtimeNanos();
            try(SQLiteStatement row=db.compileStatement("INSERT OR REPLACE INTO channels VALUES(?,?,?,?,?)")) {
                db.delete("channels",null,null);db.delete("favorites",null,null);db.delete("epg",null,null);
                for(int i=0;i<10000;i++) {
                    row.bindString(1,Integer.toString(i));row.bindString(2,i==9999?"100% literal_channel":"Synthetic channel "+i);
                    row.bindString(3,Integer.toString(i%50));row.bindString(4,i<2?"asset:///playback-test.mp4":"https://example.invalid/"+i+".m3u8");row.bindLong(5,i);row.executeInsert();
                }
                db.setTransactionSuccessful();
            } finally {db.endTransaction();}
            results.putLong("import_10000_ms",(SystemClock.elapsedRealtimeNanos()-began)/1000000);
            check(catalog.count()==10000,"Catalog count");
            check(catalog.groups().size()==52,"50 categories plus All and Favorites");
            long[] timings=new long[30];
            for(int i=0;i<30;i++) {
                long t=SystemClock.elapsedRealtimeNanos();List<Channel> page=catalog.page("","",i*250);
                timings[i]=(SystemClock.elapsedRealtimeNanos()-t)/1000000;
                check(page.size()==251,"Page bounded to 250 plus next-page lookahead");
            }
            Arrays.sort(timings);results.putLong("page_250_median_ms",timings[15]);results.putLong("page_250_p95_ms",timings[28]);
            check(catalog.page("","",9750).size()==250,"Last page has no lookahead");
            check(catalog.page("7","",0).size()==200,"Category index query");
            check(catalog.matchCount("7","")==200,"Virtual list total for category");
            check(catalog.neighbor("249","","",1).id.equals("250"),"Channel switching crosses page boundary");
            check(catalog.neighbor("9999","","",1).id.equals("0"),"Next wraps to first channel");
            check(catalog.neighbor("0","","",-1).id.equals("9999"),"Previous wraps to last channel");
            check(catalog.neighbor("7","7","",1).id.equals("57"),"Channel switching respects category");
            check(catalog.page("","% literal_",0).size()==1,"Literal search wildcard escaping");
            Channel channel=catalog.page("","",0).get(0);catalog.favorite(channel);
            check(catalog.page("*","",0).size()==1,"Favorite added");
            catalog.favorite(catalog.page("*","",0).get(0));check(catalog.page("*","",0).isEmpty(),"Favorite removed");
            catalog.putEpg("test","guide");check("guide".equals(catalog.cachedEpg("test")),"Guide cache hit");
            db.execSQL("UPDATE epg SET updated=0");check(catalog.cachedEpg("test")==null,"Stale guide excluded");
            Source s=new Source("xtream","https://example.invalid","test","not-a-real-password");
            check(s.json().equals(Vault.decrypt(Vault.encrypt(s)).json()),"Android Keystore source round trip");
            checkMigration(s);
            ContentValues meta=new ContentValues();meta.put("id","source");meta.put("value",Vault.encrypt(s));db.insertWithOnConflict("meta",null,meta,SQLiteDatabase.CONFLICT_REPLACE);
            boolean failed=false;
            try{Provider.refresh(catalog,new Source("xtream","http://127.0.0.1:1","test","test"),true);}catch(Exception expected){failed=true;}
            check(failed,"Failed provider import reports failure");check(catalog.count()==10000,"Failed import preserves previous catalog");
            check(catalog.source().endpoint.equals(s.endpoint),"Failed import preserves source atomically");
            Source preview=new Source("m3u","https://example.invalid/synthetic.m3u","","");
            meta.put("value",Vault.encrypt(preview));db.insertWithOnConflict("meta",null,meta,SQLiteDatabase.CONFLICT_REPLACE);
            long uiBegan=SystemClock.elapsedRealtime();
            android.app.Activity activity=startActivitySync(new android.content.Intent(getTargetContext(),MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("benchmark",true));
            boolean[] ready={false};
            for(int attempt=0;attempt<100&&!ready[0];attempt++) {
                SystemClock.sleep(50);runOnMainSync(()->{android.widget.ListView list=activity.findViewById(android.R.id.content).findViewWithTag("channel-list");ready[0]=activity.hasWindowFocus()&&list!=null&&list.getAdapter()!=null&&list.getAdapter().getCount()==10000&&list.getChildCount()>0;});
            }
            check(ready[0],"Virtual channel list exposes all 10,000 channels");results.putLong("cached_catalog_visible_ms",SystemClock.elapsedRealtime()-uiBegan);
            runOnMainSync(()->{android.widget.ListView list=activity.findViewById(android.R.id.content).findViewWithTag("channel-list");list.requestFocus();list.setSelection(270);});
            boolean[] pageReady={false};
            for(int attempt=0;attempt<100&&!pageReady[0];attempt++){SystemClock.sleep(50);runOnMainSync(()->{android.widget.ListView list=activity.findViewById(android.R.id.content).findViewWithTag("channel-list");Channel row=(Channel)list.getAdapter().getItem(270);pageReady[0]=row!=null&&row.id.equals("270");});}
            if(!pageReady[0])runOnMainSync(()->{android.widget.ListView list=activity.findViewById(android.R.id.content).findViewWithTag("channel-list");results.putInt("paging_count",list.getCount());results.putInt("paging_selected",list.getSelectedItemPosition());results.putInt("paging_first_visible",list.getFirstVisiblePosition());});
            check(pageReady[0],"Automatic paging loads channel 270 without Next");
            // Right from the categories must reach the channels; it used to stop in the information panel.
            runOnMainSync(()->activity.findViewById(android.R.id.content).findViewWithTag("category-list").requestFocus());
            waitForIdleSync();sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);waitForIdleSync();
            boolean[] reached={false};
            runOnMainSync(()->reached[0]=((android.view.View)activity.findViewById(android.R.id.content).findViewWithTag("channel-list")).hasFocus());
            check(reached[0],"Right from the category list reaches the channels");
            results.putBoolean("categories_right_to_channels",true);
            boolean[] operable={true};
            runOnMainSync(()->operable[0]=((android.view.ViewGroup)activity.findViewById(android.R.id.content).findViewWithTag("information-panel")).hasFocusable());
            check(!operable[0],"Information panel is read-only, with nothing to focus");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN);sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT);sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_LEFT);sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP);waitForIdleSync();
            android.graphics.Bitmap screen=getUiAutomation().takeScreenshot();
            if(screen!=null){try(java.io.FileOutputStream image=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),"benchmark.png"))){screen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image);}screen.recycle();}
            int originalPosition=-1;List<Catalog.Group> beforeOrder=catalog.groups();for(int i=0;i<beforeOrder.size();i++)if(beforeOrder.get(i).id().equals("49"))originalPosition=i;
            runOnMainSync(()->activity.findViewById(android.R.id.content).findViewWithTag("category-search").performClick());waitForIdleSync();
            android.view.accessibility.AccessibilityNodeInfo dialog=getUiAutomation().getRootInActiveWindow();
            android.view.accessibility.AccessibilityNodeInfo input=findNode(dialog,"android.widget.EditText",null);
            check(input!=null,"Category search field opens");Bundle value=new Bundle();value.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"49");input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,value);
            input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);waitForIdleSync();SystemClock.sleep(250);
            if(android.os.Build.VERSION.SDK_INT>=30)check(input.performAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()),"Keyboard search action accepted");
            else sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER);
            waitForIdleSync();waitKeyboardHidden();SystemClock.sleep(150);
            int[] filtered={0};boolean[] categoryFocus={false};runOnMainSync(()->{android.widget.ListView list=activity.findViewById(android.R.id.content).findViewWithTag("category-list");filtered[0]=list.getCount();categoryFocus[0]=list.hasFocus();list.setSelection(0);});check(filtered[0]==1,"Keyboard Enter submits category search");check(categoryFocus[0],"Category search returns focus to results");
            results.putBoolean("category_keyboard_submit",true);
            sendKeySync(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_DPAD_CENTER));SystemClock.sleep(800);sendKeySync(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_DPAD_CENTER));waitForIdleSync();
            int[] full={0};runOnMainSync(()->{android.widget.ListView list=activity.findViewById(android.R.id.content).findViewWithTag("category-list");full[0]=list.getCount();});check(full[0]==52,"Long press reveals full list for reordering");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP);sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER);waitForIdleSync();SystemClock.sleep(250);
            List<Catalog.Group> afterOrder=catalog.groups();check(afterOrder.get(originalPosition-1).id().equals("49"),"Search-result category moved in full list and saved");
            try(Catalog reopened=new Catalog(getTargetContext(),"benchmark.db")){check(reopened.groups().get(originalPosition-1).id().equals("49"),"Category order persists on reopen");}
            results.putBoolean("category_search_reorder",true);results.putBoolean("continuous_paging",true);
            runOnMainSync(()->activity.findViewById(android.R.id.content).findViewWithTag("channel-search").performClick());waitForIdleSync();
            android.view.accessibility.AccessibilityNodeInfo channelInput=waitFocusedField("Channel search field opens");
            Bundle channelValue=new Bundle();channelValue.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"Synthetic channel 9949");channelInput.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,channelValue);
            waitForIdleSync();waitKeyboardVisible();waitFocusedField("Channel search field keeps input focus");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER);waitForIdleSync();
            // Submitting hops through root.post and a database worker, so poll rather than assume one fixed delay.
            boolean[] submittedBeforeHide={false};
            for(int attempt=0;attempt<60&&!submittedBeforeHide[0];attempt++){SystemClock.sleep(50);runOnMainSync(()->{android.widget.ListView list=activity.findViewById(android.R.id.content).findViewWithTag("channel-list");submittedBeforeHide[0]=list.getCount()==1;});}
            results.putBoolean("physical_enter_submitted",submittedBeforeHide[0]);check(submittedBeforeHide[0],"Physical Enter alone submits the channel search");
            waitKeyboardHidden();
            boolean[] channelFiltered={false};for(int attempt=0;attempt<100&&!channelFiltered[0];attempt++){SystemClock.sleep(50);runOnMainSync(()->{android.widget.ListView list=activity.findViewById(android.R.id.content).findViewWithTag("channel-list");Channel row=(Channel)list.getAdapter().getItem(0);channelFiltered[0]=list.getCount()==1&&row!=null&&row.id.equals("9949")&&list.hasFocus();});}
            check(channelFiltered[0],"Physical Enter submits channel search and focuses results");results.putBoolean("channel_keyboard_submit",true);
            runOnMainSync(activity::finish);waitForIdleSync();
            android.app.Activity playback=startActivitySync(new android.content.Intent(getTargetContext(),PlayerActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("url","asset:///playback-test.mp4").putExtra("name","Synthetic playback test").putExtra("channel_id","0").putExtra("category","").putExtra("search","").putExtra("benchmark",true));
            check(bannerShows(playback,"Synthetic playback test"),"Playback names the channel in a banner");
            runOnMainSync(()->{
                android.widget.TextView strip=playback.findViewById(android.R.id.content).findViewWithTag("channel-banner");
                int tall=((android.view.View)playback.findViewById(android.R.id.content)).getHeight();
                check(strip.getHeight()>0&&tall>0&&strip.getHeight()<tall/5,"The banner is a slim strip, not a full-page scrim");
                check(playback.findViewById(android.R.id.content).findViewWithTag("playback-status")!=null,"A status line reports what the connection is doing");
            });
            boolean[] playing={false};
            for(int attempt=0;attempt<150&&!playing[0];attempt++) {
                SystemClock.sleep(50);runOnMainSync(()->{androidx.media3.ui.PlayerView player=playback.findViewById(android.R.id.content).findViewWithTag("video-player");playing[0]=player.getPlayer()!=null&&player.getPlayer().getCurrentPosition()>500&&player.getPlayer().getPlayerError()==null;});
            }
            check(playing[0],"On-device video decoding and playback advances");results.putBoolean("local_video_playback",true);
            boolean[] controls={true};runOnMainSync(()->{androidx.media3.ui.PlayerView player=playback.findViewById(android.R.id.content).findViewWithTag("video-player");controls[0]=player.getUseController();});check(!controls[0],"Playback controls disabled");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP);waitForChannel(playback,"1");
            check(bannerShows(playback,"Synthetic channel 1"),"Changing channel names the new one in the banner");
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN);waitForChannel(playback,"0");results.putBoolean("up_down_channel_switching",true);
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP);
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_UP);
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN);
            waitForChannel(playback,"1");
            results.putBoolean("rapid_channel_switching",true);
            results.putBoolean("channel_name_banner",true);
            boolean[] quiet={false};
            for(int attempt=0;attempt<100&&!quiet[0];attempt++){SystemClock.sleep(50);runOnMainSync(()->quiet[0]=((android.view.View)playback.findViewById(android.R.id.content).findViewWithTag("playback-status")).getVisibility()!=android.view.View.VISIBLE);}
            check(quiet[0],"The status line clears once the stream is playing");
            SystemClock.sleep(600);android.graphics.Bitmap videoScreen=getUiAutomation().takeScreenshot();
            if(videoScreen!=null){try(java.io.FileOutputStream image=new java.io.FileOutputStream(new java.io.File(getTargetContext().getExternalFilesDir(null),"playback.png"))){videoScreen.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image);}videoScreen.recycle();}
            runOnMainSync(playback::finish);waitForIdleSync();
            LibraryDeviceChecks.run(this,catalog,results);
            results.putString("result","PASS: continuous paging, category search and persistent full-list reorder, schema migration, filtered channel switching, hidden playback controls, favorites, guide TTL, Keystore, failed-import rollback");
            finish(-1,results);
        }catch(Throwable e){results.putString("result","FAIL: "+e.getClass().getSimpleName()+": "+e.getMessage());finish(0,results);}
        finally{catalog.close();getTargetContext().deleteDatabase("benchmark.db");}
    }
    private static void check(boolean ok,String what){if(!ok)throw new AssertionError(what);}
    private static boolean keyboardShowing(Instrumentation test){
        for(android.view.accessibility.AccessibilityWindowInfo window:test.getUiAutomation().getWindows())if(window.getType()==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD)return true;
        return false;
    }
    /** Injected keys and long presses go to the focused window, so they are lost while the IME still owns focus. */
    static void waitKeyboardHidden(Instrumentation test){
        for(int attempt=0;attempt<60;attempt++){if(!keyboardShowing(test))return;SystemClock.sleep(50);}
        throw new AssertionError("Keyboard remains visible after search");
    }
    static void waitKeyboardVisible(Instrumentation test){
        for(int attempt=0;attempt<60;attempt++){if(keyboardShowing(test))return;SystemClock.sleep(50);}
        throw new AssertionError("Keyboard never appeared for search");
    }
    static android.view.accessibility.AccessibilityNodeInfo waitFocusedField(Instrumentation test,String what){
        for(int attempt=0;attempt<60;attempt++){
            android.view.accessibility.AccessibilityNodeInfo field=findNode(test.getUiAutomation().getRootInActiveWindow(),"android.widget.EditText",null);
            if(field!=null&&field.isFocused())return field;
            SystemClock.sleep(50);
        }
        throw new AssertionError(what);
    }
    private void waitKeyboardHidden(){waitKeyboardHidden(this);}
    private void waitKeyboardVisible(){waitKeyboardVisible(this);}
    private android.view.accessibility.AccessibilityNodeInfo waitFocusedField(String what){return waitFocusedField(this,what);}
    /** The banner hides itself after a few seconds, so poll for it rather than sampling once. */
    private boolean bannerShows(android.app.Activity activity,String name){
        boolean[] shown={false};
        for(int attempt=0;attempt<60&&!shown[0];attempt++){
            runOnMainSync(()->{
                android.widget.TextView strip=activity.findViewById(android.R.id.content).findViewWithTag("channel-banner");
                shown[0]=strip!=null&&strip.getVisibility()==android.view.View.VISIBLE&&name.contentEquals(strip.getText());
            });
            if(!shown[0])SystemClock.sleep(50);
        }
        return shown[0];
    }
    private void waitForChannel(android.app.Activity activity,String id){
        boolean[] ready={false};for(int attempt=0;attempt<100&&!ready[0];attempt++){SystemClock.sleep(50);runOnMainSync(()->ready[0]=id.equals(activity.getIntent().getStringExtra("channel_id")));}check(ready[0],"Remote changes to channel "+id);
    }
    private static android.view.accessibility.AccessibilityNodeInfo findNode(android.view.accessibility.AccessibilityNodeInfo root,String className,String text){
        if(root==null)return null;
        if((className==null||className.equals(String.valueOf(root.getClassName())))&&(text==null||text.equalsIgnoreCase(root.getText()==null?"":root.getText().toString())))return root;
        for(int i=0;i<root.getChildCount();i++){var match=findNode(root.getChild(i),className,text);if(match!=null)return match;}return null;
    }
    private void checkMigration(Source source) throws Exception{
        String name="migration.db";getTargetContext().deleteDatabase(name);
        android.database.sqlite.SQLiteDatabase old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(getTargetContext().getDatabasePath(name),null);
        old.execSQL("CREATE TABLE channels(id TEXT PRIMARY KEY,name TEXT,category TEXT,url TEXT,position INTEGER)");
        old.execSQL("INSERT INTO channels VALUES('saved','Saved channel','group','https://example.invalid/live',0)");
        old.execSQL("CREATE TABLE meta(id TEXT PRIMARY KEY,value TEXT NOT NULL)");old.execSQL("INSERT INTO meta VALUES('source',?)",new Object[]{Vault.encrypt(source)});old.setVersion(1);old.close();
        try(Catalog upgraded=new Catalog(getTargetContext(),name)){
            check(upgraded.count()==1,"Schema migration keeps channels");check(upgraded.source().json().equals(source.json()),"Schema migration keeps encrypted login");
            check(upgraded.getReadableDatabase().getVersion()==6,"Schema upgraded to version 6");
        }finally{getTargetContext().deleteDatabase(name);}
    }
}
