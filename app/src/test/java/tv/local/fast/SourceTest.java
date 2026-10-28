package tv.local.fast;
import org.junit.Test;
import static org.junit.Assert.*;
public class SourceTest {
    @Test public void encodesAccountComponents() {Source s=new Source("xtream","https://example.com/","a b&c","p/a?ss");assertEquals("https://example.com/player_api.php?username=a%20b%26c&password=p%2Fa%3Fss&action=get_live_streams",s.api("get_live_streams"));assertEquals("https://example.com/live/a%20b%26c/p%2Fa%3Fss/42.m3u8",s.play(new Channel("42","Name","1","",false)));}
    @Test(expected=IllegalArgumentException.class) public void rejectsLocalFile(){new Source("m3u","file:///data/private","","");}
    @Test(expected=IllegalArgumentException.class) public void rejectsUserInfo(){new Source("m3u","https://user:pass@example.com/list","","");}
    @Test(expected=IllegalArgumentException.class) public void requiresCredentials(){new Source("xtream","https://example.com","","");}
    @Test public void roundTripsConfig() throws Exception {Source s=new Source("m3u","https://example.com/list?token=hello","","");assertEquals(s.endpoint,Source.fromJson(s.json()).endpoint);}
    @Test public void movieAndEpisodeUrlsEncodeCredentials(){Source s=new Source("xtream","https://example.com/","a b","p/a");assertEquals("https://example.com/movie/a%20b/p%2Fa/42.mkv",s.playLibrary("movie","42",".MKV"));assertEquals("https://example.com/series/a%20b/p%2Fa/99.mp4",s.playLibrary("episode","99","mp4"));}
    @Test(expected=IllegalArgumentException.class) public void rejectsInvalidVodId(){new Source("xtream","https://example.com","a","b").playLibrary("movie","../file","mp4");}
    @Test(expected=IllegalArgumentException.class) public void rejectsM3uVod(){new Source("m3u","https://example.com/list","","").playLibrary("movie","1","mp4");}
    @Test public void invalidExtensionCannotAlterUrl(){assertEquals("https://example.com/movie/a/b/1.mp4",new Source("xtream","https://example.com","a","b").playLibrary("movie","1","mp4?token=bad"));}
}
