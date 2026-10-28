package tv.local.fast;
import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;
public class GuideTest {
    @Test public void decodesProviderTitlesAndDescriptions() throws Exception {
        String s=Guide.format(new JSONObject("{\"epg_listings\":[{\"title\":\"V29ybGQgTmV3cw==\",\"description\":\"VGhlIGhlYWRsaW5lcw==\",\"start_timestamp\":1700000000}]}"));assertTrue(s.contains("World News"));assertTrue(s.contains("The headlines"));
    }
    @Test public void handlesEmptyGuide() throws Exception {assertTrue(Guide.format(new JSONObject("{\"epg_listings\":[]}")).contains("No guide"));}
    @Test public void limitsGuideLength() throws Exception {
        var list=new org.json.JSONArray();for(int i=0;i<100;i++)list.put(new JSONObject().put("title","UHJvZ3JhbQ==").put("start","12:00"));
        assertEquals(6,Guide.format(new JSONObject().put("epg_listings",list)).split("Program",-1).length-1);
    }
}
