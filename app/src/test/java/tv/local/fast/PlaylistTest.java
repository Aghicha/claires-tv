package tv.local.fast;

import org.junit.Test;
import java.io.StringReader;
import java.util.*;
import static org.junit.Assert.*;

public class PlaylistTest {
    @Test public void importsQuotedCommaRelativeUrlsAndStableIds() throws Exception {
        String data="\uFEFF#EXTM3U\n#EXTINF:-1 group-title=\"News, local\",World News\n../stream.m3u8?token=a%20b\n#EXTINF:-1,Second\nhttps://example.com/two.ts\n";
        List<Channel> rows=new ArrayList<>();assertEquals(2,Playlist.parse(new StringReader(data),"https://example.com/list/channels.m3u",rows::add));
        assertEquals("News, local",rows.get(0).category);assertEquals("World News",rows.get(0).name);assertEquals("https://example.com/stream.m3u8?token=a%20b",rows.get(0).url);
        List<Channel> again=new ArrayList<>();Playlist.parse(new StringReader(data),"https://example.com/list/channels.m3u",again::add);assertEquals(rows.get(0).id,again.get(0).id);
    }
    @Test(expected=java.io.IOException.class) public void rejectsHtmlLoginPage() throws Exception {Playlist.parse(new StringReader("<html>Login</html>"),"https://example.com/",c->{});}
    @Test(expected=java.io.IOException.class) public void rejectsHlsMasterAsChannelList() throws Exception {Playlist.parse(new StringReader("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000000\nmain.m3u8"),"https://example.com/",c->{});}
    @Test public void skipsUnsupportedSchemes() throws Exception {assertEquals(0,Playlist.parse(new StringReader("#EXTM3U\n#EXTINF:-1,Not playable\nfile:///etc/passwd"),"https://example.com/",c->fail()));}
    @Test public void handlesTenThousandWithoutBuildingAList() throws Exception {
        StringBuilder data=new StringBuilder("#EXTM3U\n");for(int i=0;i<10000;i++)data.append("#EXTINF:-1 group-title=\"Group\",Channel ").append(i).append("\nhttps://example.com/").append(i).append(".ts\n");
        int[] count={0};assertEquals(10000,Playlist.parse(new StringReader(data.toString()),"https://example.com/",c->count[0]++));assertEquals(10000,count[0]);
    }
}
