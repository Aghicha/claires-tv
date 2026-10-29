package tv.local.fast;

import android.app.*;
import android.content.SharedPreferences;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.*;
import java.util.concurrent.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class F1Activity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Channel[] channels=new Channel[6];
    private final ExoPlayer[] players=new ExoPlayer[6];
    private final FrameLayout[] cells=new FrameLayout[6];
    private Catalog catalog; private Source source; private SharedPreferences prefs; private TvUi ui;
    private LinearLayout root; private String sourceKey; private int focused; private boolean active;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().getDecorView().setSystemUiVisibility(5894);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui=new TvUi(this);catalog=new Catalog(getApplicationContext(),BuildConfig.DEBUG&&getIntent().getBooleanExtra("benchmark",false)?"benchmark.db":"catalog.db");
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(12),dp(16),dp(12));root.setBackgroundColor(TvUi.BG);setContentView(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);TextView title=ui.text("F1 View",22,TvUi.INK);title.setTypeface(null,android.graphics.Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,dp(48),1));
        header.addView(ui.text("Move to hear a channel · OK to change it",14,TvUi.MUTED));header.addView(ui.button("Back",this::finish));root.addView(header);
        LinearLayout grid=new LinearLayout(this);root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout left=new LinearLayout(this);left.setOrientation(LinearLayout.VERTICAL);grid.addView(left,new LinearLayout.LayoutParams(0,-1,1));
        addCell(left,0,2);addCell(left,1,1);
        LinearLayout right=new LinearLayout(this);right.setOrientation(LinearLayout.VERTICAL);grid.addView(right,new LinearLayout.LayoutParams(0,-1,1));
        for(int row=0;row<2;row++){LinearLayout line=new LinearLayout(this);right.addView(line,new LinearLayout.LayoutParams(-1,0,1));for(int col=0;col<2;col++)addCell(line,2+row*2+col,1);}
        io.execute(()->{try{Source s=catalog.source();if(s==null){main.post(this::finish);return;}SharedPreferences p=getSharedPreferences("f1_view",MODE_PRIVATE);String key=Integer.toHexString(s.json().hashCode());Channel[] saved=new Channel[6];for(int i=0;i<6;i++){String id=p.getString(key+":"+i,"");if(!id.isEmpty())saved[i]=catalog.channel(id);}main.post(()->{if(isFinishing()||isDestroyed())return;source=s;sourceKey=key;prefs=p;System.arraycopy(saved,0,channels,0,6);renderAll();cells[focused].requestFocus();});}catch(Exception e){main.post(()->{Toast.makeText(this,"Could not load F1 View",Toast.LENGTH_SHORT).show();finish();});}});
    }
    private void addCell(LinearLayout parent,int i,int weight){
        FrameLayout cell=new FrameLayout(this);cells[i]=cell;cell.setFocusable(true);cell.setPadding(dp(3),dp(3),dp(3),dp(3));
        cell.setOnFocusChangeListener((v,has)->{if(has){focused=i;updateAudio();}paintBorders();});cell.setOnClickListener(v->picker(i,"",0));
        boolean vertical=parent.getOrientation()==LinearLayout.VERTICAL;
        LinearLayout.LayoutParams lp=vertical?new LinearLayout.LayoutParams(-1,0,weight):new LinearLayout.LayoutParams(0,-1,weight);
        lp.setMargins(dp(3),dp(3),dp(3),dp(3));parent.addView(cell,lp);
    }
    private String key(int slot){return sourceKey+":"+slot;}
    @Override protected void onStart(){super.onStart();active=true;if(source!=null)renderAll();}
    @Override protected void onStop(){active=false;for(int i=0;i<6;i++)release(i);super.onStop();}
    private void release(int i){if(players[i]!=null){players[i].release();players[i]=null;}}
    private void renderAll(){for(int i=0;i<6;i++)render(i);paintBorders();updateAudio();}
    private void render(int i){
        release(i);FrameLayout cell=cells[i];cell.removeAllViews();Channel channel=channels[i];
        if(channel!=null&&active){DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent("VLC/3.0.20 LibVLC/3.0.20").setConnectTimeoutMs(10000).setReadTimeoutMs(15000);
            ExoPlayer player=new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(new androidx.media3.datasource.DefaultDataSource.Factory(this,http))).build();players[i]=player;
            if(i!=0)player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setMaxVideoSize(640,360).setMaxVideoFrameRate(30).setForceLowestBitrate(true).build());
            PlayerView video=(PlayerView)getLayoutInflater().inflate(R.layout.f1_player,cell,false);video.setUseController(false);video.setFocusable(false);video.setPlayer(player);cell.addView(video,new FrameLayout.LayoutParams(-1,-1));
            TextView error=ui.text("",13,TvUi.INK);error.setBackgroundColor(0xb0111916);error.setPadding(dp(8),dp(4),dp(8),dp(4));error.setVisibility(View.GONE);cell.addView(error,new FrameLayout.LayoutParams(-1,-2,Gravity.CENTER));
            player.addListener(new Player.Listener(){@Override public void onPlayerError(PlaybackException e){error.setText("Stream unavailable · OK to change channel");error.setVisibility(View.VISIBLE);}@Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_READY)error.setVisibility(View.GONE);}});
            player.setVolume(i==focused?1:0);player.setMediaItem(MediaItem.fromUri(source.play(channel)));player.prepare();player.play();
        }
        TextView label=ui.text(channel==null?"+ Add channel":channel.name,15,TvUi.INK);label.setPadding(dp(10),dp(5),dp(10),dp(5));label.setBackgroundColor(0xb0111916);label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);cell.addView(label,new FrameLayout.LayoutParams(-1,channel==null?-1:dp(36),channel==null?Gravity.CENTER:Gravity.BOTTOM));
    }
    private void paintBorders(){for(int i=0;i<6;i++){cells[i].setBackground(ui.shape(cells[i].hasFocus()?TvUi.ACCENT:TvUi.SURFACE,0));}}
    private void updateAudio(){for(int i=0;i<6;i++)if(players[i]!=null)players[i].setVolume(i==focused?1:0);}
    private void picker(int slot,String term,int offset){
        io.execute(()->{try{List<Channel> result=catalog.page("",term,offset);boolean more=result.size()>Catalog.PAGE;if(more)result=new ArrayList<>(result.subList(0,Catalog.PAGE));List<Channel> found=result;main.post(()->{if(isFinishing()||isDestroyed())return;ArrayList<String> labels=new ArrayList<>();labels.add("Search channels");if(channels[slot]!=null)labels.add("Remove channel");if(offset>0)labels.add("Previous page");for(Channel c:found)labels.add(c.name);if(more)labels.add("Next page");
            new AlertDialog.Builder(this).setTitle("Choose channel"+(term.isEmpty()?"":" · "+term)).setItems(labels.toArray(new String[0]),(d,which)->{int n=0;if(which==n++){TvUi.searchDialog(this,root,"Search channels","Channel name",term,value->picker(slot,value,0));return;}if(channels[slot]!=null&&which==n++){save(slot,null);return;}if(offset>0&&which==n++){picker(slot,term,Math.max(0,offset-Catalog.PAGE));return;}int index=which-n;if(index>=0&&index<found.size())save(slot,found.get(index));else if(more)picker(slot,term,offset+Catalog.PAGE);}).setNegativeButton("Cancel",null).show();});}catch(Exception e){main.post(()->Toast.makeText(this,"Could not load channels",Toast.LENGTH_SHORT).show());}});
    }
    private void save(int slot,Channel channel){channels[slot]=channel;SharedPreferences.Editor edit=prefs.edit();if(channel==null)edit.remove(key(slot));else edit.putString(key(slot),channel.id);edit.apply();render(slot);paintBorders();updateAudio();cells[slot].requestFocus();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);io.shutdownNow();Catalog c=catalog;new Thread(()->{try{io.awaitTermination(40,TimeUnit.SECONDS);}catch(InterruptedException ignored){}c.close();}).start();super.onDestroy();}
}
