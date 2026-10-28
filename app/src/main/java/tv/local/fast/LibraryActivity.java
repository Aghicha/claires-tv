package tv.local.fast;

import android.app.*;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import static tv.local.fast.TvUi.*;

/**
 * Films and Series. Browsing is a category column beside a poster grid; opening a title replaces that with a
 * detail page carrying the artwork, everything the provider knows, and a Watch button. A series detail page
 * also lists its seasons and episodes. Live TV keeps its own three-column browser in MainActivity.
 */
public final class LibraryActivity extends Activity {
    private static final int COLUMNS=5, POSTER_WIDTH=150, POSTER_HEIGHT=210;
    private final ExecutorService io=Executors.newSingleThreadExecutor(),details=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Catalog catalog;
    private Source source;
    private TvUi ui;
    private Posters posters;
    private LinearLayout root;
    private TextView status;
    // browse
    private TextView groupHeading,itemHeading;
    private ListView groupList;
    private GridView grid;
    private Button groupSearchButton,itemSearchButton,refresh;
    private ArrayAdapter<Catalog.Group> groupAdapter;
    private Titles titles;
    // detail
    private ImageView detailPoster;
    private TextView detailTitle,detailFacts;
    private Button watch,favorite;
    private LinearLayout seasonRow,detailExtra;
    private ListView episodeList;
    private EpisodeRows episodeRows;

    private final List<Catalog.Group> allGroups=new ArrayList<>(),groups=new ArrayList<>();
    private String kind="movie",category="",search="",groupSearch="",moving=null,episodeSearch="",resumeEpisodeId="";
    private List<Catalog.Group> originalOrder;
    private LibraryItem selected,activeSeries;
    private String selectedExtension="",selectedImage="";
    private LibraryData.Episode selectedEpisode;
    private LibraryData.Series series;
    private LibraryData.Season season;
    private Future<?> catalogTask,detailTask;
    private int generation,pageVersion,detailVersion,restoreRow;
    private boolean loading,renderingGroups,ignoreReorderRelease,detailMode;

    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().getDecorView().setSystemUiVisibility(5894);ui=new TvUi(this);posters=Posters.get(this);
        catalog=new Catalog(getApplicationContext(),BuildConfig.DEBUG&&getIntent().getBooleanExtra("benchmark",false)?"benchmark.db":"catalog.db");
        kind="series".equals(getIntent().getStringExtra("kind"))?"series":"movie";browse();showStatus("Opening saved library…");
        io.execute(()->{try{Source saved=catalog.source();post(()->{source=saved;if(source==null)showStatus("Add your provider in Live TV → Settings → Source.");else openKind(kind,false);});}
            catch(Exception e){post(()->showStatus("Your saved source could not be opened. Return to Live TV → Settings → Source."));}});
    }
    private void post(Runnable r){main.post(()->{if(!isDestroyed()&&!isFinishing())r.run();});}
    private void showStatus(String value){status.setText(value);status.setVisibility(View.VISIBLE);}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_SHORT).show();}
    private void cancelDetails(){++detailVersion;if(detailTask!=null)detailTask.cancel(true);}
    private void live(){startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("benchmark",benchmark()));finish();}
    private boolean benchmark(){return BuildConfig.DEBUG&&getIntent().getBooleanExtra("benchmark",false);}

    private LinearLayout shell(String activeNav){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(ui.dp(24),ui.dp(16),ui.dp(24),ui.dp(16));root.setBackgroundColor(BG);setContentView(root);
        LinearLayout header=ui.header(activeNav,this::live,()->openKind("movie",false),()->openKind("series",false));
        refresh=ui.button("Refresh",()->{if(!loading)openKind(kind,true);});refresh.setTag("library-refresh");header.addView(refresh);root.addView(header);
        status=ui.text("",14,MUTED);status.setMinHeight(ui.dp(36));status.setVisibility(View.GONE);root.addView(status);
        return root;
    }

    // ---------- browse: categories beside a poster grid ----------
    private void browse(){
        detailMode=false;series=null;season=null;selectedEpisode=null;activeSeries=null;
        LinearLayout content=shell(kind.equals("movie")?"Films":"Series");
        LinearLayout panels=new LinearLayout(this);content.addView(panels,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout left=new LinearLayout(this);left.setOrientation(LinearLayout.VERTICAL);panels.addView(left,new LinearLayout.LayoutParams(0,-1,26));
        LinearLayout leftHeader=new LinearLayout(this);
        groupHeading=ui.text("Categories",15,MUTED);leftHeader.addView(groupHeading,new LinearLayout.LayoutParams(0,ui.dp(48),1));
        groupSearchButton=ui.button("Search",this::searchGroups);groupSearchButton.setTag("library-category-search");leftHeader.addView(groupSearchButton);left.addView(leftHeader);
        groupList=new ListView(this);groupList.setTag("library-categories");ui.list(groupList);left.addView(groupList,new LinearLayout.LayoutParams(-1,0,1));
        groupAdapter=new ArrayAdapter<Catalog.Group>(this,android.R.layout.simple_list_item_1,groups){
            @Override public View getView(int position,View reuse,ViewGroup parent){
                TextView row=(TextView)super.getView(position,reuse,parent);Catalog.Group group=groups.get(position);
                row.setText((group.id().equals(moving)?"↕  ":"")+group);row.setTextColor(INK);row.setTextSize(14);row.setSingleLine(true);
                row.setEllipsize(TextUtils.TruncateAt.END);row.setMinHeight(ui.dp(48));row.setPadding(ui.dp(12),ui.dp(6),ui.dp(12),ui.dp(6));return row;
            }
        };groupList.setAdapter(groupAdapter);

        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);right.setPadding(ui.dp(14),0,0,0);
        right.setTag("library-grid-panel");panels.addView(right,new LinearLayout.LayoutParams(0,-1,74));
        LinearLayout rightHeader=new LinearLayout(this);
        itemHeading=ui.text(kind.equals("movie")?"Films":"Series",15,MUTED);rightHeader.addView(itemHeading,new LinearLayout.LayoutParams(0,ui.dp(48),1));
        itemSearchButton=ui.button("Search",this::searchItems);itemSearchButton.setTag("library-search");rightHeader.addView(itemSearchButton);right.addView(rightHeader);
        grid=new GridView(this);grid.setTag("library-list");grid.setNumColumns(COLUMNS);
        grid.setHorizontalSpacing(ui.dp(12));grid.setVerticalSpacing(ui.dp(12));grid.setSelector(ui.rowSelector());
        grid.setCacheColorHint(BG);grid.setFocusable(true);
        titles=new Titles();grid.setAdapter(titles);right.addView(grid,new LinearLayout.LayoutParams(-1,0,1));

        groupList.setOnItemClickListener((p,v,pos,id)->{if(moving!=null)finishReorder(true);else chooseGroup(pos);});
        groupList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(!renderingGroups&&moving==null)chooseGroup(pos);}});
        groupList.setOnItemLongClickListener((p,v,pos,id)->{if(loading||pos>=groups.size())return false;beginReorder(groups.get(pos).id());return true;});
        groupList.setOnKeyListener((v,key,event)->{
            if(moving==null)return false;
            if(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){if(event.getAction()==KeyEvent.ACTION_DOWN)moveGroup(key==KeyEvent.KEYCODE_DPAD_UP?-1:1);return true;}
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER){
                if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)ignoreReorderRelease=false;
                if(event.getAction()==KeyEvent.ACTION_UP){if(ignoreReorderRelease)ignoreReorderRelease=false;else finishReorder(true);}return true;}
            return key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT;
        });
        grid.setOnItemClickListener((p,v,pos,id)->{LibraryItem item=titles.at(pos);if(item!=null)openDetail(item);});
        grid.setOnItemLongClickListener((p,v,pos,id)->{LibraryItem item=titles.at(pos);if(item==null)return false;toggleFavorite(item);return true;});
    }
    private void openKind(String next,boolean force){
        if(source==null){showStatus("Add your source in Live TV → Settings → Source.");return;}
        if(catalogTask!=null)catalogTask.cancel(true);cancelDetails();
        kind=next;category="";search="";groupSearch="";moving=null;selected=null;restoreRow=0;
        int token=++generation;++pageVersion;browse();loading=true;refresh.setEnabled(false);
        allGroups.clear();groups.clear();groupAdapter.notifyDataSetChanged();// otherwise the other library's categories sit under the new heading
        if(!source.type.equals("xtream")){loading=false;refresh.setEnabled(true);showStatus("Films and Series need an Xtream source. Your M3U source remains available in Live TV.");return;}
        showStatus("Opening saved "+(kind.equals("movie")?"films":"series")+"…");String requestKind=kind;
        catalogTask=io.submit(()->{
            try{
                if(force||!catalog.mediaImported(requestKind)){
                    post(()->{if(token==generation)showStatus("Loading "+(requestKind.equals("movie")?"films":"series")+" from your provider…");});
                    LibraryProvider.refresh(catalog,source,requestKind,count->post(()->{if(token==generation)showStatus("Loading "+count+" titles…");}));
                }
                List<Catalog.Group> result=catalog.mediaGroups(requestKind);
                post(()->{if(token!=generation)return;loading=false;refresh.setEnabled(true);status.setVisibility(View.GONE);
                    allGroups.clear();allGroups.addAll(result);renderGroups(category);loadTitles(true);});
            }catch(Exception e){
                android.util.Log.w("ClaireTV","library_failed kind="+requestKind+" cause="+e.getClass().getSimpleName());
                // The reason belongs in the status line: loadSavedAfterFailure repaints the rest of the screen.
                String reason=e instanceof java.io.IOException&&e.getMessage()!=null?e.getMessage():"Check the source and subscription, then try Refresh.";
                post(()->{if(token!=generation)return;loading=false;refresh.setEnabled(true);showStatus(reason+" Your saved catalog is kept; use Refresh to retry.");loadSavedAfterFailure(requestKind,token);});
            }
        });
    }
    private void loadSavedAfterFailure(String requestKind,int token){
        io.execute(()->{try{List<Catalog.Group> saved=catalog.mediaGroups(requestKind);
            post(()->{if(token==generation){allGroups.clear();allGroups.addAll(saved);renderGroups(category);loadTitles(true);}});}catch(Exception ignored){}});
    }
    private void loadTitles(boolean focus){
        if(detailMode)return;
        int token=++pageVersion;String requestKind=kind,filter=category,term=search;
        io.execute(()->{try{int count=catalog.mediaCount(requestKind,filter,term);List<LibraryItem> rows=catalog.mediaPage(requestKind,filter,term,0);
            post(()->{if(token!=pageVersion||detailMode)return;
                titles.reset(count,requestKind,filter,term,rows,token);
                int position=Math.min(restoreRow,Math.max(0,count-1));restoreRow=0;grid.setSelection(position);
                itemHeading.setText((kind.equals("movie")?"Films":"Series")+(count>0?"  ·  "+count:""));
                if(count==0&&filter.equals(Catalog.CONTINUE))showStatus("Nothing to continue yet. A film or episode you stop partway through appears here.");
                else if(count==0)showStatus("No matching titles. Try another category or search; if the library is empty your provider may not include it.");
                else status.setVisibility(View.GONE);
                if(focus)grid.requestFocus();
            });
        }catch(Exception e){post(()->toast("Could not read saved titles. Try Refresh."));}});
    }
    private void renderGroups(String focusId){
        renderingGroups=true;groups.clear();String term=groupSearch.toLowerCase(Locale.ROOT);
        for(Catalog.Group group:allGroups)if(group.name().toLowerCase(Locale.ROOT).contains(term))groups.add(group);
        groupAdapter.notifyDataSetChanged();int pos=0;for(int i=0;i<groups.size();i++)if(groups.get(i).id().equals(focusId))pos=i;
        groupList.setSelection(pos);groupList.post(()->renderingGroups=false);
    }
    private void chooseGroup(int position){
        if(position<0||position>=groups.size()||loading)return;
        String id=groups.get(position).id();
        if(!category.equals(id)){category=id;restoreRow=0;loadTitles(false);}
    }
    private void searchGroups(){
        if(moving!=null)finishReorder(true);
        TvUi.searchDialog(this,root,"Search categories","Category name",groupSearch,value->{
            groupSearch=value;renderGroups(category);groupList.requestFocus();
            if(groups.isEmpty())toast("No matching categories. Search again or clear the search.");});
    }
    private void searchItems(){
        TvUi.searchDialog(this,root,kind.equals("movie")?"Search films":"Search series","Title",search,value->{
            search=value;restoreRow=0;loadTitles(true);});
    }

    // ---------- detail: artwork, everything known, and a Watch button ----------
    private void openDetail(LibraryItem item){
        cancelDetails();detailMode=true;restoreRow=Math.max(0,grid.getSelectedItemPosition());
        selected=item;selectedExtension=item.extension;selectedImage=item.image;selectedEpisode=null;series=null;season=null;episodeSearch="";resumeEpisodeId="";
        int token=detailVersion;
        boolean movie=kind.equals("movie");
        LinearLayout column=shell(movie?"Films":"Series");
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setTag("library-detail");
        column.addView(page,new LinearLayout.LayoutParams(-1,0,1));

        // A film can spend the whole page on its description; a series must leave room for its episodes.
        int art=movie?300:210;
        LinearLayout top=new LinearLayout(this);
        page.addView(top,movie?new LinearLayout.LayoutParams(-1,0,1):new LinearLayout.LayoutParams(-1,ui.dp(art)));
        detailPoster=new ImageView(this);detailPoster.setTag("library-poster");detailPoster.setScaleType(ImageView.ScaleType.FIT_START);
        LinearLayout.LayoutParams poster=new LinearLayout.LayoutParams(ui.dp(Math.round(art*0.7f)),ui.dp(art));poster.setMarginEnd(ui.dp(20));top.addView(detailPoster,poster);
        posters.load(detailPoster,item.image,ui.dp(Math.round(art*0.7f)),ui.dp(art),R.drawable.poster_placeholder);

        LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);top.addView(info,new LinearLayout.LayoutParams(0,-1,1));
        detailTitle=ui.text(item.name,24,INK);detailTitle.setTypeface(null,Typeface.BOLD);detailTitle.setMaxLines(2);detailTitle.setEllipsize(TextUtils.TruncateAt.END);info.addView(detailTitle);
        ScrollView factsScroll=new ScrollView(this);factsScroll.setFocusable(false);
        detailFacts=ui.text(LibraryData.describe(item.summary),14,MUTED);detailFacts.setGravity(Gravity.TOP);detailFacts.setPadding(0,ui.dp(8),ui.dp(12),ui.dp(8));
        factsScroll.addView(detailFacts);info.addView(factsScroll,new LinearLayout.LayoutParams(-1,0,1));

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.VERTICAL);actions.setPadding(ui.dp(16),0,0,0);top.addView(actions,new LinearLayout.LayoutParams(ui.dp(230),-2));
        watch=ui.button(movie?"Watch":"Loading episodes…",this::watchPressed);watch.setTag("library-play");watch.setId(View.generateViewId());
        watch.setLayoutParams(new LinearLayout.LayoutParams(-1,ui.dp(52)));actions.addView(watch);
        favorite=ui.button(item.favorite?"Remove from favorites":"Add to favorites",()->toggleFavorite(selected));
        favorite.setTag("library-favorite");favorite.setId(View.generateViewId());favorite.setLayoutParams(new LinearLayout.LayoutParams(-1,ui.dp(52)));actions.addView(favorite);

        detailExtra=new LinearLayout(this);detailExtra.setOrientation(LinearLayout.VERTICAL);detailExtra.setPadding(0,ui.dp(10),0,0);
        page.addView(detailExtra,movie?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(-1,0,1));
        watch.requestFocus();

        if(movie){
            watch.setEnabled(true);
            detailTask=details.submit(()->{
                try{
                    LibraryData.Detail data=LibraryProvider.movie(catalog,source,item);
                    long resume=catalog.playbackPosition(item.progressKey());
                    post(()->{if(token!=detailVersion||!detailMode)return;
                        selectedExtension=data.extension();
                        if(!data.image().isEmpty()){selectedImage=data.image();posters.load(detailPoster,data.image(),ui.dp(220),ui.dp(310),R.drawable.poster_placeholder);}
                        detailFacts.setText(data.description()+(resume>0?"\n\nStopped at "+time(resume):""));
                        watch.setText(resume>0?"Resume from "+time(resume):"Watch");});
                }catch(Exception e){post(()->{if(token==detailVersion&&detailMode)detailFacts.setText(LibraryData.describe(item.summary)+"\n\nFull details are unavailable. You can still watch this film.");});}
            });
        } else {
            watch.setEnabled(false);
            activeSeries=item;
            detailTask=details.submit(()->{
                try{
                    LibraryData.Series data=LibraryProvider.series(catalog,source,item);
                    String resume=catalog.resumeEpisode(item.id);
                    post(()->{if(token!=detailVersion||!detailMode)return;resumeEpisodeId=resume;showSeasons(data);});
                }catch(Exception e){post(()->{if(token!=detailVersion||!detailMode)return;
                    watch.setText("Seasons unavailable");detailFacts.setText(LibraryData.describe(item.summary)+"\n\nSeasons could not load. Press Back and open the series again to retry.");});}
            });
        }
    }
    private void showSeasons(LibraryData.Series data){
        series=data;
        if(!data.image().isEmpty()){selectedImage=data.image();posters.load(detailPoster,data.image(),ui.dp(220),ui.dp(310),R.drawable.poster_placeholder);}
        if(!data.description().isEmpty())detailFacts.setText(data.description());
        if(data.seasons().isEmpty()){watch.setText("No episodes");detailFacts.append("\n\nThe provider has not supplied any playable episodes for this series.");return;}
        watch.setText("Watch first episode");watch.setEnabled(true);

        detailExtra.removeAllViews();
        LinearLayout seasonHeader=new LinearLayout(this);seasonHeader.setGravity(Gravity.CENTER_VERTICAL);
        seasonHeader.addView(ui.text("Seasons",15,MUTED),new LinearLayout.LayoutParams(0,ui.dp(44),1));
        Button episodeSearchButton=ui.button("Search episodes",()->TvUi.searchDialog(this,root,"Search episodes","Episode title",episodeSearch,value->{
            episodeSearch=value;filterEpisodes();episodeList.requestFocus();}));
        episodeSearchButton.setTag("library-episode-search");episodeSearchButton.setId(View.generateViewId());seasonHeader.addView(episodeSearchButton);detailExtra.addView(seasonHeader);

        HorizontalScrollView seasonScroll=new HorizontalScrollView(this);seasonScroll.setHorizontalScrollBarEnabled(false);
        seasonRow=new LinearLayout(this);seasonRow.setTag("library-seasons");seasonScroll.addView(seasonRow);detailExtra.addView(seasonScroll);
        for(LibraryData.Season s:data.seasons()){
            Button b=ui.button(s.name()+"  ·  "+s.episodes().size(),()->chooseSeason(s));b.setTag("season-"+s.id());b.setId(View.generateViewId());seasonRow.addView(b);
        }
        detailExtra.addView(ui.text("Episodes",15,MUTED),new LinearLayout.LayoutParams(-1,ui.dp(40)));
        episodeList=new ListView(this);episodeList.setTag("library-episodes");ui.list(episodeList);episodeList.setId(View.generateViewId());
        episodeRows=new EpisodeRows();episodeList.setAdapter(episodeRows);
        detailExtra.addView(episodeList,new LinearLayout.LayoutParams(-1,0,1));

        // Geometric focus search will not step from the buttons into the list across these nested rows, so the
        // Down path is wired by hand: actions → season buttons → episodes.
        int firstSeason=seasonRow.getChildCount()>0?seasonRow.getChildAt(0).getId():episodeList.getId();
        favorite.setNextFocusDownId(firstSeason);
        watch.setNextFocusDownId(favorite.getId());
        episodeSearchButton.setNextFocusDownId(episodeList.getId());
        for(int i=0;i<seasonRow.getChildCount();i++){
            View b=seasonRow.getChildAt(i);b.setNextFocusDownId(episodeList.getId());b.setNextFocusUpId(favorite.getId());
        }
        episodeList.setNextFocusUpId(firstSeason);
        // Opening a part-watched series lands on the season holding the episode it stopped on.
        for(LibraryData.Season s:data.seasons())
            for(LibraryData.Episode e:s.episodes())
                if(e.id().equals(resumeEpisodeId)){chooseSeason(s);return;}
        episodeList.setOnItemClickListener((p,v,pos,id)->{LibraryData.Episode e=episodeRows.at(pos);if(e!=null){selectedEpisode=e;playEpisode(e);}});
        episodeList.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int pos,long id){LibraryData.Episode e=episodeRows.at(pos);if(e!=null){selectedEpisode=e;watchLabel();}}});
        chooseSeason(data.seasons().get(0));
    }
    private void chooseSeason(LibraryData.Season chosen){
        season=chosen;episodeSearch="";
        for(int i=0;i<seasonRow.getChildCount();i++){
            Button b=(Button)seasonRow.getChildAt(i);boolean active=("season-"+chosen.id()).equals(b.getTag());
            b.setBackground(active?ui.shape(ACCENT,0):null);if(active)b.setTextColor(BG);else{b.setTextColor(INK);b.setBackground(ui.focus());}
        }
        filterEpisodes();
    }
    private void filterEpisodes(){
        List<LibraryData.Episode> rows=new ArrayList<>();String term=episodeSearch.toLowerCase(Locale.ROOT);
        if(season!=null)for(LibraryData.Episode e:season.episodes())if(e.title().toLowerCase(Locale.ROOT).contains(term))rows.add(e);
        episodeRows.set(rows);
        int at=0;for(int i=0;i<rows.size();i++)if(rows.get(i).id().equals(resumeEpisodeId))at=i;
        episodeList.setSelection(at);
        selectedEpisode=rows.isEmpty()?null:rows.get(at);
        watchLabel();
    }
    private void watchLabel(){
        watch.setEnabled(selectedEpisode!=null);
        if(selectedEpisode==null)watch.setText("No matching episodes");
        else if(selectedEpisode.id().equals(resumeEpisodeId))watch.setText("Resume episode "+selectedEpisode.number());
        else watch.setText("Watch episode "+selectedEpisode.number());
    }
    private void watchPressed(){
        if(kind.equals("movie")){
            if(selected!=null)playMedia("movie",selected.id,selected.name,selectedExtension,selected.progressKey());
            return;
        }
        if(selectedEpisode!=null)playEpisode(selectedEpisode);
    }
    private void playEpisode(LibraryData.Episode episode){
        if(activeSeries==null)return;
        playMedia("episode",episode.id(),activeSeries.name+" · "+episode.title(),episode.extension(),"episode:"+activeSeries.id+":"+episode.id());
    }
    private void playMedia(String type,String id,String name,String ext,String key){
        Source current=source;
        io.execute(()->{try{
            long resume=catalog.playbackPosition(key);String url=current.playLibrary(type,id,ext);
            post(()->startActivity(new Intent(this,PlayerActivity.class).putExtra("url",url).putExtra("name",name)
                .putExtra("content_key",key).putExtra("start_position",resume).putExtra("benchmark",benchmark())));
        }catch(Exception e){post(()->toast("Could not open this title. Try another title or Refresh."));}});
    }
    private static String time(long millis){long minutes=millis/60000;return (minutes/60)+":"+String.format(Locale.ROOT,"%02d",minutes%60);}

    private void toggleFavorite(LibraryItem item){
        if(item==null)return;
        io.execute(()->{try{
            catalog.mediaFavorite(item);
            post(()->{
                toast(item.favorite?"Removed from favorites":"Added to favorites");
                LibraryItem updated=new LibraryItem(item.kind,item.id,item.name,item.category,item.extension,item.summary,item.image,!item.favorite);
                if(detailMode){selected=updated;favorite.setText(updated.favorite?"Remove from favorites":"Add to favorites");}
                else{restoreRow=Math.max(0,grid.getSelectedItemPosition());loadTitles(true);}
            });
        }catch(Exception e){post(()->toast("Favorite could not be saved. Try again."));}});
    }
    private void beginReorder(String id){originalOrder=new ArrayList<>(allGroups);moving=id;ignoreReorderRelease=true;groupSearch="";groupHeading.setText("Move · OK saves");renderGroups(id);groupList.requestFocus();}
    private void moveGroup(int step){int at=-1;for(int i=0;i<allGroups.size();i++)if(allGroups.get(i).id().equals(moving))at=i;if(at<0||at+step<0||at+step>=allGroups.size())return;Collections.swap(allGroups,at,at+step);renderGroups(moving);}
    private void finishReorder(boolean save){
        if(moving==null)return;String id=moving;moving=null;groupHeading.setText("Categories");
        if(!save&&originalOrder!=null){allGroups.clear();allGroups.addAll(originalOrder);}originalOrder=null;renderGroups(id);
        if(save){List<Catalog.Group> order=new ArrayList<>(allGroups);String requestKind=kind;
            io.execute(()->{try{catalog.saveMediaOrder(requestKind,order);post(()->toast("Category order saved"));}catch(Exception e){post(()->toast("Could not save the order. Try again."));}});}
    }

    /** Poster grid backed by the same bounded paging as the live channel list. */
    private final class Titles extends BaseAdapter {
        private final LinkedHashMap<Integer,List<LibraryItem>> pages=new LinkedHashMap<>(8,.75f,true);
        private final Set<Integer> pending=new HashSet<>();
        private int total,token;private String requestKind,filter,term;
        void reset(int count,String kind,String category,String search,List<LibraryItem> first,int version){
            total=count;requestKind=kind;filter=category;term=search;token=version;pages.clear();pending.clear();pages.put(0,first);notifyDataSetChanged();
        }
        LibraryItem at(int position){
            if(position<0||position>=total)return null;
            List<LibraryItem> page=pages.get(position/Catalog.PAGE);int index=position%Catalog.PAGE;
            return page!=null&&index<page.size()?page.get(index):null;
        }
        void requestPage(int page){
            if(page<0||page*Catalog.PAGE>=total||pages.containsKey(page)||!pending.add(page))return;
            int version=token;String requested=requestKind,filterCategory=filter,filterSearch=term;
            io.execute(()->{try{List<LibraryItem> rows=catalog.mediaPage(requested,filterCategory,filterSearch,page*Catalog.PAGE);
                post(()->{if(pageVersion!=version||detailMode)return;pending.remove(page);pages.put(page,rows);
                    while(pages.size()>8)pages.remove(pages.keySet().iterator().next());notifyDataSetChanged();});
            }catch(Exception e){post(()->{pending.remove(page);toast("Could not read titles. Scroll again to retry.");});}});
        }
        public int getCount(){return total;}
        public Object getItem(int position){return at(position);}
        public long getItemId(int position){return position;}
        public View getView(int position,View reuse,ViewGroup parent){
            View row=reuse!=null&&reuse.getTag(R.id.poster_cell) instanceof Cell?reuse:newCell();
            Cell cell=(Cell)row.getTag(R.id.poster_cell);
            LibraryItem item=at(position);
            if(item==null){cell.label.setText("Loading…");posters.load(cell.poster,"",ui.dp(POSTER_WIDTH),ui.dp(POSTER_HEIGHT),R.drawable.poster_placeholder);requestPage(position/Catalog.PAGE);}
            else{cell.label.setText((item.favorite?"★  ":"")+item.name);posters.load(cell.poster,item.image,ui.dp(POSTER_WIDTH),ui.dp(POSTER_HEIGHT),R.drawable.poster_placeholder);}
            return row;
        }
        private View newCell(){
            LinearLayout box=new LinearLayout(LibraryActivity.this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(ui.dp(6),ui.dp(6),ui.dp(6),ui.dp(6));
            ImageView poster=new ImageView(LibraryActivity.this);poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(poster,new LinearLayout.LayoutParams(-1,ui.dp(POSTER_HEIGHT)));
            TextView label=ui.text("",13,INK);label.setMaxLines(2);label.setEllipsize(TextUtils.TruncateAt.END);label.setMinHeight(ui.dp(40));label.setPadding(0,ui.dp(6),0,0);
            box.addView(label,new LinearLayout.LayoutParams(-1,-2));
            box.setTag(R.id.poster_cell,new Cell(poster,label));return box;
        }
    }
    private static final class Cell {final ImageView poster;final TextView label;Cell(ImageView poster,TextView label){this.poster=poster;this.label=label;}}

    private final class EpisodeRows extends BaseAdapter {
        private List<LibraryData.Episode> rows=Collections.emptyList();
        void set(List<LibraryData.Episode> value){rows=value;notifyDataSetChanged();}
        LibraryData.Episode at(int position){return position>=0&&position<rows.size()?rows.get(position):null;}
        public int getCount(){return rows.size();}
        public Object getItem(int position){return at(position);}
        public long getItemId(int position){return position;}
        public View getView(int position,View reuse,ViewGroup parent){
            TextView row=reuse instanceof TextView?(TextView)reuse:ui.text("",15,INK);
            LibraryData.Episode e=rows.get(position);
            row.setPadding(ui.dp(12),ui.dp(8),ui.dp(12),ui.dp(8));row.setMinHeight(ui.dp(56));row.setMaxLines(2);row.setEllipsize(TextUtils.TruncateAt.END);
            row.setText(e.number()+".  "+e.title());return row;
        }
    }

    @Override public void onBackPressed(){
        if(moving!=null){finishReorder(false);return;}
        if(detailMode){cancelDetails();detailMode=false;browse();renderGroups(category);loadTitles(true);return;}
        super.onBackPressed();
    }
    @Override public void onResume(){
        super.onResume();
        // Returning from playback: what is resumable has changed, so refresh it without rebuilding the page.
        if(detailMode&&selected!=null&&kind.equals("movie")){
            LibraryItem item=selected;int token=detailVersion;
            io.execute(()->{long resume=catalog.playbackPosition(item.progressKey());
                post(()->{if(token==detailVersion&&detailMode&&watch!=null)watch.setText(resume>0?"Resume from "+time(resume):"Watch");});});
        } else if(detailMode&&activeSeries!=null&&series!=null){
            LibraryItem item=activeSeries;int token=detailVersion;
            io.execute(()->{String resume=catalog.resumeEpisode(item.id);
                post(()->{if(token==detailVersion&&detailMode&&series!=null){resumeEpisodeId=resume;watchLabel();}});});
        } else if(!detailMode&&source!=null&&!loading&&!allGroups.isEmpty()){
            int token=generation;String requestKind=kind;
            io.execute(()->{try{List<Catalog.Group> result=catalog.mediaGroups(requestKind);
                post(()->{if(token!=generation||detailMode)return;allGroups.clear();allGroups.addAll(result);renderGroups(category);
                    if(category.equals(Catalog.CONTINUE))loadTitles(false);});
            }catch(Exception ignored){}});
        }
    }
    @Override public void onDestroy(){
        cancelDetails();main.removeCallbacksAndMessages(null);io.shutdownNow();details.shutdownNow();
        Catalog close=catalog;new Thread(()->{try{io.awaitTermination(40,TimeUnit.SECONDS);details.awaitTermination(40,TimeUnit.SECONDS);}catch(InterruptedException ignored){}close.close();},"library-close").start();
        super.onDestroy();
    }
}
