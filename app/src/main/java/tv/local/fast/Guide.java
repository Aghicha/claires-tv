package tv.local.fast;

import org.json.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

final class Guide {
    private static String decode(String s) {
        if(s.isEmpty())return s;
        try {return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8);}catch(IllegalArgumentException e){return s;}
    }
    static String format(JSONObject object) {
        JSONArray list=object.optJSONArray("epg_listings");
        if(list==null||list.length()==0)return "No guide listings supplied for this channel.";
        StringBuilder out=new StringBuilder();SimpleDateFormat clock=new SimpleDateFormat("EEE HH:mm",Locale.getDefault());
        for(int i=0;i<Math.min(6,list.length());i++) {
            JSONObject p=list.optJSONObject(i);if(p==null)continue;
            long start=p.optLong("start_timestamp",0);String time=start>0?clock.format(new Date(start*1000)):p.optString("start");
            out.append(time).append("\n").append(decode(p.optString("title"))).append("\n");
            if(i==0) {String desc=decode(p.optString("description"));if(!desc.isEmpty())out.append(desc).append("\n");}
            out.append("\n");
        }
        return out.toString().trim();
    }
}
