package tv.local.fast;

import android.app.*;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int BG=0xff111916, SURFACE=0xff1c2922, INK=0xffedf4ef, MUTED=0xffb5c7bb, ACCENT=0xffb8dcc8;
    private final ExecutorService io=Executors.newSingleThreadExecutor(), epg=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final long opened=SystemClock.elapsedRealtime();
    private Catalog catalog;
    private Source source;
    private LinearLayout root, body;
    private TextView status, guide, selectedTitle, categoryTitle;
    private ListView groupList, channelList;
    private Button refresh, categorySearch;
    private Rows channels;
    private String category="",search="",groupSearch="",movingGroup=null;
    private int offset=0,queryVersion=0,guideVersion=0;
    private boolean importing=false,setup=false,reported=false;
    private Runnable guideTask;
    private Future<?> guideRequest;
    private Channel selected;
    private final ArrayList<Catalog.Group> groups=new ArrayList<>();
    private final ArrayList<Catalog.Group> allGroups=new ArrayList<>();
    private ArrayList<Catalog.Group> originalOrder;
    private ArrayAdapter<Catalog.Group> groupAdapter;
    private boolean renderingGroups;
    private boolean ignoreReorderRelease;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(5894);
        catalog=new Catalog(getApplicationContext(),BuildConfig.DEBUG&&getIntent().getBooleanExtra("benchmark",false)?"benchmark.db":"catalog.db");
        shell();
        io.execute(()->{
            try {Source saved=catalog.source();post(()->{source=saved;if(saved==null)showSetup();else loadGroups(true);});}
            catch(Exception e){post(()->{notice("Saved source could not be opened. Enter your source again.");showSetup();});}
        });
    }
    private void post(Runnable r) {main.post(()->{if(!isDestroyed()&&!isFinishing())r.run();});}
    private void notice(String message){if(importing){status.setText(message);status.setVisibility(View.VISIBLE);}else Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color) {
        TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;
    }
    private GradientDrawable shape(int color,int stroke) {
        GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(5));if(stroke!=0)g.setStroke(dp(2),stroke);return g;
    }
    private StateListDrawable focusBackground() {
        StateListDrawable d=new StateListDrawable();d.addState(new int[]{android.R.attr.state_focused},shape(0xff304d3c,ACCENT));
        d.addState(new int[]{android.R.attr.state_pressed},shape(0xff304d3c,ACCENT));d.addState(new int[]{},shape(SURFACE,0));return d;
    }
    private Button button(String label,Runnable click) {
        Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setTextColor(INK);b.setMinHeight(dp(48));
        b.setBackground(focusBackground());b.setPadding(dp(12),0,dp(12),0);b.setOnClickListener(v->click.run());
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(48));p.setMarginEnd(dp(8));b.setLayoutParams(p);return b;
    }
    private void shell() {
        setup=false;root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(16),dp(24),dp(16));root.setBackgroundColor(BG);setContentView(root);
        TvUi ui=new TvUi(this);LinearLayout top=ui.header("Live TV",()->{},()->openLibrary("movie"),()->openLibrary("series"));
        refresh=button("Settings",()->{});refresh.setOnClickListener(v->{PopupMenu menu=new PopupMenu(this,refresh);menu.getMenu().add("Refresh live TV");menu.getMenu().add("Source");menu.setOnMenuItemClickListener(item->{if(item.getTitle().equals("Source"))showSetup();else if(source!=null&&!importing)refreshSource(source,false);return true;});menu.show();});top.addView(refresh);root.addView(top);
        status=text("",14,MUTED);status.setMinHeight(dp(36));status.setVisibility(View.GONE);root.addView(status);
        body=new LinearLayout(this);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout left=new LinearLayout(this);left.setOrientation(LinearLayout.VERTICAL);body.addView(left,new LinearLayout.LayoutParams(0,-1,27));
        LinearLayout groupHeading=new LinearLayout(this);groupHeading.setGravity(Gravity.CENTER_VERTICAL);
        categoryTitle=text("Categories",15,MUTED);groupHeading.addView(categoryTitle,new LinearLayout.LayoutParams(0,dp(48),1));
        categorySearch=button("Search",this::showCategorySearch);categorySearch.setTag("category-search");groupHeading.addView(categorySearch);left.addView(groupHeading);
        groupList=new ListView(this);groupList.setTag("category-list");groupList.setId(View.generateViewId());configureList(groupList);left.addView(groupList,new LinearLayout.LayoutParams(-1,0,1));
        groupAdapter=new ArrayAdapter<Catalog.Group>(this,android.R.layout.simple_list_item_1,groups){
            @Override public View getView(int position,View reuse,ViewGroup parent){
                TextView t=(TextView)super.getView(position,reuse,parent);Catalog.Group group=groups.get(position);
                t.setText((group.id().equals(movingGroup)?"↕  ":"")+group);t.setTextColor(INK);t.setTextSize(14);t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);t.setMinHeight(dp(48));t.setPadding(dp(12),dp(6),dp(12),dp(6));return t;
            }
        };groupList.setAdapter(groupAdapter);
        LinearLayout middle=new LinearLayout(this);middle.setPadding(dp(12),0,dp(12),0);middle.setOrientation(LinearLayout.VERTICAL);body.addView(middle,new LinearLayout.LayoutParams(0,-1,46));
        LinearLayout channelHeader=new LinearLayout(this);channelHeader.addView(text("Channels",15,MUTED),new LinearLayout.LayoutParams(0,dp(48),1));Button channelSearch=button("Search",this::showSearch);channelSearch.setTag("channel-search");channelHeader.addView(channelSearch);middle.addView(channelHeader);
        channelList=new ListView(this);channelList.setTag("channel-list");channelList.setId(View.generateViewId());configureList(channelList);middle.addView(channelList,new LinearLayout.LayoutParams(-1,0,1));
        channels=new Rows();channelList.setAdapter(channels);
        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);right.setPadding(dp(12),0,0,0);right.setTag("information-panel");body.addView(right,new LinearLayout.LayoutParams(0,-1,27));
        TextView infoHeading=text("Information",15,MUTED);infoHeading.setMinHeight(dp(48));right.addView(infoHeading);
        selectedTitle=text("Select a channel",18,INK);right.addView(selectedTitle);
        // Information is read, never operated: nothing in it takes focus, so Right from the categories reaches
        // the channels instead of stopping in the guide. OK plays a channel and holding OK favorites it.
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setFocusable(false);guide=text("The guide appears here when you select a channel.",13,MUTED);guide.setGravity(Gravity.TOP);guide.setPadding(0,dp(12),dp(8),dp(12));scroll.addView(guide);right.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        groupList.setNextFocusRightId(channelList.getId());channelList.setNextFocusLeftId(groupList.getId());
        groupList.setOnItemClickListener((p,v,position,id)->{if(movingGroup!=null)finishReorder(true);else chooseGroup(position);});
        groupList.setOnItemLongClickListener((p,v,position,id)->{if(position<groups.size())beginReorder(groups.get(position).id());return true;});
        groupList.setOnKeyListener((v,key,event)->{
            if(movingGroup==null)return false;
            if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){if(event.getAction()==KeyEvent.ACTION_DOWN)moveGroup(key==KeyEvent.KEYCODE_DPAD_UP?-1:1);return true;}
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){
                if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)ignoreReorderRelease=false;
                if(event.getAction()==KeyEvent.ACTION_UP){if(ignoreReorderRelease)ignoreReorderRelease=false;else finishReorder(true);}return true;
            }
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)return true;
            return false;
        });
        groupList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(!renderingGroups&&movingGroup==null&&pos<groups.size()&&!category.equals(groups.get(pos).id()))chooseGroup(pos);}});
        channelList.setOnItemClickListener((p,v,pos,id)->{Channel c=channels.at(pos);if(c!=null)play(c);});
        channelList.setOnItemLongClickListener((p,v,pos,id)->{Channel c=channels.at(pos);if(c!=null){selected=c;toggleFavorite();}return true;});
        channelList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int pos,long id){Channel c=channels.at(pos);if(c!=null)select(c);else{selected=null;selectedTitle.setText("Loading channel…");guide.setText("");channels.requestPage(pos/Catalog.PAGE);}}});
    }
    private void openLibrary(String kind){
        Intent intent=new Intent(this,LibraryActivity.class).putExtra("kind",kind).putExtra("benchmark",BuildConfig.DEBUG&&getIntent().getBooleanExtra("benchmark",false));startActivity(intent);
    }
    private void configureList(ListView list) {
        list.setDivider(new android.graphics.drawable.ColorDrawable(BG));list.setDividerHeight(dp(5));list.setCacheColorHint(BG);
        StateListDrawable selector=new StateListDrawable();selector.addState(new int[]{android.R.attr.state_focused},shape(0xff304d3c,ACCENT));selector.addState(new int[]{},shape(0xff23372b,0));
        list.setSelector(selector);list.setDrawSelectorOnTop(false);list.setItemsCanFocus(false);
    }
    private void chooseGroup(int position) {if(position<0||position>=groups.size())return;category=groups.get(position).id();offset=0;loadPage(false);}
    private void loadGroups(boolean focus) {
        io.execute(()->{try {List<Catalog.Group> result=catalog.groups();post(()->{
            if(setup)return;allGroups.clear();allGroups.addAll(result);renderGroups(category);loadPage(focus);
        });}catch(Exception e){post(()->notice("Could not read the local catalog. Try Refresh."));}});
    }
    private void renderGroups(String focusId){
        renderingGroups=true;groups.clear();String term=groupSearch.toLowerCase(Locale.ROOT);
        for(Catalog.Group g:allGroups)if(g.name().toLowerCase(Locale.ROOT).contains(term))groups.add(g);
        groupAdapter.notifyDataSetChanged();int position=0;for(int i=0;i<groups.size();i++)if(groups.get(i).id().equals(focusId))position=i;
        groupList.setSelection(position);categorySearch.setText(groupSearch.isEmpty()?"Search":"Search / clear");
        groupList.post(()->renderingGroups=false);
    }
    private void showCategorySearch(){
        if(movingGroup!=null)finishReorder(true);
        searchDialog("Search categories","Category name",groupSearch,value->{
            groupSearch=value;renderGroups(category);groupList.requestFocus();if(groups.isEmpty())notice("No matching categories. Use Search to try another name.");
        });
    }
    private void beginReorder(String id){
        if(importing)return;originalOrder=new ArrayList<>(allGroups);movingGroup=id;ignoreReorderRelease=true;groupSearch="";categoryTitle.setText("Move · OK saves");renderGroups(id);groupList.requestFocus();
    }
    private void moveGroup(int step){
        int at=-1;for(int i=0;i<allGroups.size();i++)if(allGroups.get(i).id().equals(movingGroup))at=i;
        int target=at+step;if(at<0||target<0||target>=allGroups.size())return;
        Collections.swap(allGroups,at,target);renderGroups(movingGroup);
    }
    private void finishReorder(boolean save){
        if(movingGroup==null)return;String id=movingGroup;movingGroup=null;categoryTitle.setText("Categories");
        if(!save&&originalOrder!=null){allGroups.clear();allGroups.addAll(originalOrder);}originalOrder=null;renderGroups(id);
        if(save){List<Catalog.Group> order=new ArrayList<>(allGroups);io.execute(()->{try{catalog.saveGroupOrder(order);post(()->notice("Category order saved"));}catch(Exception e){post(()->notice("Could not save category order. Try again."));}});}
    }
    private void loadPage(boolean focus) {
        int token=++queryVersion;String filter=category,term=search;
        io.execute(()->{
            try {int count=catalog.matchCount(filter,term);List<Channel> result=catalog.page(filter,term,0);
                post(()->{if(token!=queryVersion||setup)return;channels.reset(count,filter,term,result,token);channelList.setSelection(0);
                    if(!result.isEmpty())select(result.get(0));else{selected=null;selectedTitle.setText("No channels");guide.setText("Choose another category or change your search.");}
                    if(focus)channelList.requestFocus();
                    if(!reported){reported=true;android.util.Log.i("FastTV","catalog_ready_ms="+(SystemClock.elapsedRealtime()-opened));reportFullyDrawn();}
                });
            } catch(Exception e){post(()->{if(token==queryVersion)notice("Could not read channels. Try Refresh.");});}
        });
    }
    private void select(Channel channel) {
        selected=channel;selectedTitle.setText(channel.name);guide.setText("Loading guide…");int token=++guideVersion;Source current=source;
        if(guideTask!=null)main.removeCallbacks(guideTask);if(guideRequest!=null)guideRequest.cancel(true);
        guideTask=()->guideRequest=epg.submit(()->{
            try {String result=Provider.guide(catalog,current,channel);post(()->{if(token==guideVersion&&!setup)guide.setText(result);});}
            catch(Exception e){post(()->{if(token==guideVersion&&!setup)guide.setText("Guide unavailable. You can still play this channel. Select it again to retry.");});}
        });main.postDelayed(guideTask,450);
    }
    private void play(Channel channel) {
        if(source==null)return;
        Intent intent=new Intent(this,PlayerActivity.class);intent.putExtra("url",source.play(channel));intent.putExtra("name",channel.name);
        intent.putExtra("channel_id",channel.id);intent.putExtra("category",category);intent.putExtra("search",search);intent.putExtra("source_type",source.type);
        intent.putExtra("benchmark",BuildConfig.DEBUG&&getIntent().getBooleanExtra("benchmark",false));startActivity(intent);
    }
    private void toggleFavorite() {
        Channel channel=selected;if(channel==null||importing)return;
        int oldPosition=channelList.getSelectedItemPosition();
        io.execute(()->{try{catalog.favorite(channel);post(()->{notice(channel.favorite?"Removed from favorites":"Added to favorites");
            channels.updateFavorite(channel);channelList.setSelection(Math.max(0,oldPosition));
        });}catch(Exception e){post(()->notice("Favorite could not be saved. Try again."));}});
    }
    private void showSearch() {
        searchDialog("Search saved channels","Channel name",search,value->{search=value;offset=0;loadPage(true);});
    }
    private void searchDialog(String title,String hint,String initial,java.util.function.Consumer<String> apply){
        TvUi.searchDialog(this,root,title,hint,initial,apply);
    }
    private EditText field(LinearLayout parent,String label,boolean password) {
        parent.addView(text(label,14,MUTED));EditText edit=new EditText(this);edit.setTextColor(INK);edit.setSingleLine(true);edit.setTextSize(18);
        edit.setInputType(password?129:android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setMinHeight(dp(48));parent.addView(edit,new LinearLayout.LayoutParams(-1,dp(48)));return edit;
    }
    private void showSetup() {
        if(importing){notice("Wait for the current refresh to finish.");return;}
        if(movingGroup!=null)finishReorder(true);
        setup=true;++queryVersion;++guideVersion;if(guideTask!=null)main.removeCallbacks(guideTask);if(guideRequest!=null)guideRequest.cancel(true);
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(48),dp(20),dp(48),dp(32));form.setBackgroundColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(form);setContentView(scroll);
        TextView heading=text("Connect your channels",26,INK);heading.setTypeface(null,Typeface.BOLD);form.addView(heading);
        form.addView(text("Use the source details supplied by your provider.",15,MUTED));
        Spinner type=new Spinner(this);type.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Xtream Codes","M3U playlist URL"}));form.addView(type,new LinearLayout.LayoutParams(-1,dp(48)));
        EditText address=field(form,"Server address or full M3U URL",false),user=field(form,"Username (Xtream only)",false),password=field(form,"Password (Xtream only)",true);
        if(source!=null){type.setSelection(source.type.equals("m3u")?1:0);address.setText(source.endpoint);user.setText(source.user);password.setText(source.password);}
        TextView note=text("HTTPS is preferred. An http:// source sends account details without encryption.",13,MUTED);note.setPadding(0,dp(8),0,dp(8));form.addView(note);
        TextView error=text("",14,0xffffc5b6);form.addView(error);
        Button save=button("Save and load channels",()->{
            try {
                Source candidate=new Source(type.getSelectedItemPosition()==0?"xtream":"m3u",address.getText().toString(),user.getText().toString(),password.getText().toString());
                boolean replace=source==null||!source.json().equals(candidate.json());
                ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(address.getWindowToken(),0);
                if(replace&&source!=null) new AlertDialog.Builder(this).setTitle("Replace source?").setMessage("Saved live channels, films, series, favorites and playback positions will be replaced after the new source loads successfully.")
                    .setPositiveButton("Replace",(d,w)->refreshSource(candidate,true)).setNegativeButton("Cancel",null).show();
                else refreshSource(candidate,replace);
            } catch(Exception e){error.setText(e instanceof IllegalArgumentException?e.getMessage():"Check the source details.");}
        });form.addView(save);if(source!=null)form.addView(button("Back to channels",()->{shell();loadGroups(true);}));address.requestFocus();
    }
    private void refreshSource(Source candidate,boolean replacing) {
        if(importing)return;importing=true;shell();refresh.setEnabled(false);notice("Loading your live channel catalog… This first download depends on your provider.");
        io.execute(()->{try {int count=Provider.refresh(catalog,candidate,replacing);post(()->{source=candidate;importing=false;refresh.setEnabled(true);status.setVisibility(View.GONE);category="";search="";offset=0;notice("Loaded "+count+" channels");loadGroups(true);});}
            catch(Exception e){post(()->{importing=false;refresh.setEnabled(true);String message=e instanceof java.net.SocketTimeoutException?"Provider timed out. Check the address and try Refresh.":e instanceof java.io.IOException?e.getMessage():"Could not load channels. Check the source and account.";
                status.setVisibility(View.GONE);guide.setText("The previous saved catalog is kept when a refresh fails.");
                new AlertDialog.Builder(this).setTitle("Channels could not load").setMessage(message).setPositiveButton("OK",null).show();if(source!=null)loadGroups(false);});}});
    }
    private final class Rows extends BaseAdapter {
        private final LinkedHashMap<Integer,List<Channel>> pages=new LinkedHashMap<>(8,.75f,true);
        private final Set<Integer> pending=new HashSet<>();
        private int total,version;
        private String filter="",term="";
        void reset(int count,String category,String search,List<Channel> first,int token){
            total=count;filter=category;term=search;version=token;pages.clear();pending.clear();put(0,first);notifyDataSetChanged();
        }
        private void put(int page,List<Channel> values){
            pages.put(page,new ArrayList<>(values.subList(0,Math.min(values.size(),Catalog.PAGE))));
            while(pages.size()>8)pages.remove(pages.keySet().iterator().next());
        }
        Channel at(int position){
            if(position<0||position>=total)return null;List<Channel> page=pages.get(position/Catalog.PAGE);int index=position%Catalog.PAGE;
            return page!=null&&index<page.size()?page.get(index):null;
        }
        void requestPage(int page){
            if(page<0||page*Catalog.PAGE>=total||pages.containsKey(page)||!pending.add(page))return;
            int token=version;String category=filter,search=term;
            io.execute(()->{
                try{List<Channel> result=catalog.page(category,search,page*Catalog.PAGE);post(()->{
                    if(version!=token||queryVersion!=token||setup)return;pending.remove(page);put(page,result);notifyDataSetChanged();
                    int selectedPosition=channelList.getSelectedItemPosition();if(selectedPosition/Catalog.PAGE==page){Channel c=at(selectedPosition);if(c!=null)select(c);}
                });}catch(Exception e){post(()->{if(version==token){pending.remove(page);notice("Could not load channels. Scroll again to retry.");}});}
            });
        }
        void updateFavorite(Channel old){
            for(List<Channel> page:pages.values())for(int i=0;i<page.size();i++){Channel c=page.get(i);if(c.id.equals(old.id)){Channel updated=new Channel(c.id,c.name,c.category,c.url,!old.favorite);page.set(i,updated);selected=updated;}}
            notifyDataSetChanged();
        }
        public int getCount(){return total;}public Object getItem(int p){return at(p);}public long getItemId(int p){return p;}
        public View getView(int position,View reuse,ViewGroup parent) {
            TextView row=reuse instanceof TextView?(TextView)reuse:text("",15,INK);row.setPadding(dp(12),dp(6),dp(12),dp(6));
            row.setMinHeight(dp(48));row.setMaxLines(2);row.setEllipsize(TextUtils.TruncateAt.END);Channel c=at(position);
            row.setText(c==null?"Loading…":(c.favorite?"★  ":"")+c.name);if(c==null)requestPage(position/Catalog.PAGE);return row;
        }
    }
    @Override public void onBackPressed() {if(movingGroup!=null){finishReorder(false);return;}if(setup&&source!=null){shell();loadGroups(true);}else super.onBackPressed();}
    @Override public void onDestroy() {
        main.removeCallbacksAndMessages(null);if(guideRequest!=null)guideRequest.cancel(true);io.shutdownNow();epg.shutdownNow();
        Catalog toClose=catalog;new Thread(()->{try{io.awaitTermination(40,TimeUnit.SECONDS);epg.awaitTermination(40,TimeUnit.SECONDS);}catch(InterruptedException ignored){}toClose.close();},"catalog-close").start();super.onDestroy();
    }
}
