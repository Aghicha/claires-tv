package tv.local.fast;

import android.app.Activity;
import android.os.*;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.concurrent.*;

@androidx.annotation.OptIn(markerClass=androidx.media3.common.util.UnstableApi.class)
public final class PlayerActivity extends Activity {
    private static final String PLAYER_AGENT="VLC/3.0.20 LibVLC/3.0.20";
    private static final int BANNER_MS=4000, RETRIES=3;
    private ExoPlayer player;
    private PlayerView view;
    private TextView banner,status;
    private Runnable hideBanner;
    private int retries;
    private Catalog catalog;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile String channelId;
    private int switchVersion;
    private boolean active;
    private long lastChange;
    private Source playbackSource;
    private boolean onDemand;
    private String progressKey;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);getWindow().getDecorView().setSystemUiVisibility(5894);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        catalog=new Catalog(getApplicationContext(),BuildConfig.DEBUG&&getIntent().getBooleanExtra("benchmark",false)?"benchmark.db":"catalog.db");
        channelId=getIntent().getStringExtra("channel_id");
        progressKey=getIntent().getStringExtra("content_key");onDemand=progressKey!=null;
        FrameLayout root=new FrameLayout(this);view=new PlayerView(this);view.setTag("video-player");
        view.setUseController(onDemand);view.setControllerAutoShow(false);view.setKeepContentOnPlayerReset(true);view.setShowNextButton(false);view.setShowPreviousButton(false);
        root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        // A slim translucent strip, not a scrim: the picture stays visible behind the channel name.
        banner=new TextView(this);banner.setTextColor(0xffffffff);banner.setTextSize(15);banner.setPadding(dp(24),dp(9),dp(24),dp(9));
        banner.setBackgroundColor(0x80101814);banner.setSingleLine(true);banner.setEllipsize(android.text.TextUtils.TruncateAt.END);
        banner.setVisibility(android.view.View.GONE);banner.setTag("channel-banner");
        root.addView(banner,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        // Anything the connection is doing is reported quietly along the bottom instead of over the picture.
        status=new TextView(this);status.setTextColor(0xffd6e6dc);status.setTextSize(13);status.setPadding(dp(24),dp(7),dp(24),dp(7));
        status.setBackgroundColor(0x80101814);status.setVisibility(android.view.View.GONE);status.setTag("playback-status");
        root.addView(status,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START));
        setContentView(root);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    /** Names the channel or title briefly on start and on every channel change, then gets out of the way. */
    private void showBanner(){
        String name=getIntent().getStringExtra("name");
        if(name==null||name.isEmpty())return;
        banner.setText(name);banner.setVisibility(android.view.View.VISIBLE);
        if(hideBanner!=null)main.removeCallbacks(hideBanner);
        hideBanner=()->banner.setVisibility(android.view.View.GONE);
        main.postDelayed(hideBanner,BANNER_MS);
    }
    private void showStatus(String text){status.setText(text);status.setVisibility(android.view.View.VISIBLE);}
    private void hideStatus(){status.setVisibility(android.view.View.GONE);}
    @Override public void onStart() {
        super.onStart();active=true;
            // Xtream panels commonly answer 406 on /movie and /series unless the agent looks like a media player.
            DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent(PLAYER_AGENT).setConnectTimeoutMs(10000).setReadTimeoutMs(15000);
            player=new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(new androidx.media3.datasource.DefaultDataSource.Factory(this,http))).build();view.setPlayer(player);
            player.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state){
                    if(state==Player.STATE_READY){
                        retries=0;hideStatus();
                        if(onDemand)android.util.Log.i("ClaireTV",progressKey.startsWith("episode:")?"episode_ready":"film_ready");
                    } else if(state==Player.STATE_BUFFERING){
                        showStatus(retries>0?"Retrying the connection… ("+retries+" of "+RETRIES+")":"Fetching the stream…");
                    } else if(state==Player.STATE_ENDED){
                        showStatus(onDemand?"Finished. Press Back to return to the library.":"Stream ended. Up/Down changes channel; Back returns to the list.");
                        if(onDemand&&player!=null){long duration=player.getDuration();io.execute(()->catalog.savePlayback(progressKey,0,duration));}
                    }
                }
                @Override public void onPlayerError(PlaybackException error){
                    // The bare error code cannot tell "not in this subscription" (403) from "wrong container" (404).
                    int code=0;
                    for(Throwable t=error;t!=null;t=t.getCause())if(t instanceof androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException){code=((androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)t).responseCode;break;}
                    String detail=error.getErrorCodeName()+(code!=0?", HTTP "+code:"");
                    android.util.Log.w("ClaireTV","playback_error="+detail);
                    // A dropped connection is worth retrying quietly; a refused or missing stream is not.
                    if(recoverable(error,code)&&retries<RETRIES&&player!=null){
                        retries++;showStatus("Connection lost. Retrying… ("+retries+" of "+RETRIES+")");
                        main.postDelayed(()->{if(active&&player!=null){player.prepare();player.play();}},1200L*retries);
                        return;
                    }
                    showStatus("Stream could not play ("+detail+"). "+(onDemand?"Press Back to return to the library.":"Up/Down changes channel; Back returns to the list."));
                }
            });
            play(getIntent().getStringExtra("url"));view.hideController();view.requestFocus();
            if(onDemand){long position=getIntent().getLongExtra("start_position",0);if(position>0)player.seekTo(position);}
    }
    /** Network and timeout failures are worth another attempt; an HTTP refusal or a bad address is not. */
    private static boolean recoverable(PlaybackException error,int code){
        if(code>=400&&code<600)return false;
        int e=error.errorCode;
        return e==PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
            ||e==PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            ||e==PlaybackException.ERROR_CODE_IO_UNSPECIFIED;
    }
    private void play(String url){
        try{
            if(!(BuildConfig.DEBUG&&"asset:///playback-test.mp4".equals(url)))Source.normalize(url);
            retries=0;showBanner();showStatus("Fetching the stream…");
            player.setMediaItem(MediaItem.fromUri(url));player.prepare();player.play();
        }catch(Exception e){showStatus("Invalid stream address. Press Back to return to channels.");}
    }
    private void changeChannel(int direction){
        if(channelId==null||!active)return;
        int token=++switchVersion;String category=getIntent().getStringExtra("category"),search=getIntent().getStringExtra("search");
        io.execute(()->{
            try{
                if(playbackSource==null)playbackSource=catalog.source();Source source=playbackSource;Channel next=catalog.neighbor(channelId,category==null?"":category,search==null?"":search,direction);
                if(next==null||source==null)return;channelId=next.id;String url=source.play(next);
                main.post(()->{if(!active||token!=switchVersion||isDestroyed())return;getIntent().putExtra("channel_id",next.id).putExtra("url",url).putExtra("name",next.name);play(url);});
            }catch(Exception e){main.post(()->{if(active&&token==switchVersion)showStatus("Could not change channel. Press Back to choose one from the list.");});}
        });
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int key=event.getKeyCode();
        if(!onDemand&&(key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN)){
            if(event.getAction()==KeyEvent.ACTION_DOWN&&(event.getRepeatCount()==0||SystemClock.elapsedRealtime()-lastChange>=180)){lastChange=SystemClock.elapsedRealtime();changeChannel(key==KeyEvent.KEYCODE_DPAD_UP?-1:1);}return true;
        }
        if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE){if(event.getAction()==KeyEvent.ACTION_DOWN&&player!=null){if(player.isPlaying())player.pause();else player.play();}return true;}
        if(onDemand&&(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER)&&!view.isControllerFullyVisible()){
            if(event.getAction()==KeyEvent.ACTION_DOWN)view.showController();return true;
        }
        if(onDemand&&!view.isControllerFullyVisible()&&(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT)){
            if(event.getAction()==KeyEvent.ACTION_DOWN&&player!=null&&player.isCurrentMediaItemSeekable()){long position=Math.max(0,player.getCurrentPosition()+(key==KeyEvent.KEYCODE_DPAD_RIGHT?10000:-10000));long duration=player.getDuration();if(duration>0)position=Math.min(position,duration);player.seekTo(position);}return true;
        }
        if(onDemand&&view.dispatchKeyEvent(event))return true;
        return super.dispatchKeyEvent(event);
    }
    @Override public void onBackPressed(){if(onDemand&&view.isControllerFullyVisible()){view.hideController();return;}super.onBackPressed();}
    @Override public void onStop(){active=false;++switchVersion;main.removeCallbacksAndMessages(null);if(player!=null){
        if(onDemand){long position=player.getCurrentPosition(),duration=player.getDuration();getIntent().putExtra("start_position",position);io.execute(()->catalog.savePlayback(progressKey,position,duration));}
        view.setPlayer(null);player.release();player=null;}super.onStop();}
    @Override public void onDestroy(){io.shutdown();Catalog close=catalog;new Thread(()->{try{io.awaitTermination(20,TimeUnit.SECONDS);}catch(InterruptedException ignored){}close.close();},"player-catalog-close").start();super.onDestroy();}
}
