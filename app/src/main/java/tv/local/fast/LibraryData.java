package tv.local.fast;

import org.json.*;
import java.util.*;

final class LibraryData {
    record Episode(String id,String title,String extension,String season,int number,String summary,String image) {}
    record Season(String id,String name,List<Episode> episodes) {}
    record Series(String description,List<Season> seasons,String image) {}
    record Detail(String description,String extension,String image) {}

    /** Detail endpoints carry the larger artwork under several names, and backdrops arrive as an array. */
    static String artwork(JSONObject j){
        if(j==null)return "";
        String direct=string(j,"movie_image","cover_big","cover","poster_path","image");
        if(!direct.isEmpty())return Posters.clean(direct);
        JSONArray backdrops=j.optJSONArray("backdrop_path");
        return backdrops!=null&&backdrops.length()>0?Posters.clean(backdrops.optString(0)):"";
    }

    static String string(JSONObject j,String... keys){
        if(j==null)return "";
        for(String key:keys){String s=j.optString(key,"").trim();if(!s.isEmpty()&&!s.equals("null")&&!s.equals("0"))return s;}
        return "";
    }
    static String describe(JSONObject j){
        if(j==null)return "No additional information supplied.";
        StringBuilder out=new StringBuilder();
        line(out,"Released",string(j,"releasedate","releaseDate","release_date","year"));
        line(out,"Genre",string(j,"genre"));line(out,"Rating",string(j,"rating","rating_5based"));
        line(out,"Duration",string(j,"duration","episode_run_time"));
        String plot=string(j,"plot","description");if(!plot.isEmpty())out.append("\n").append(plot).append("\n");
        line(out,"Cast",string(j,"cast","actors"));line(out,"Director",string(j,"director"));
        return out.length()==0?"No additional information supplied.":out.toString().trim();
    }
    private static void line(StringBuilder out,String label,String value){if(!value.isEmpty())out.append(label).append(": ").append(value).append("\n");}
    static String describe(String json){try{return describe(new JSONObject(json));}catch(JSONException e){return "No additional information supplied.";}}
    static Detail movie(String json,String fallbackExtension) throws JSONException{
        JSONObject root=new JSONObject(json);JSONObject info=root.optJSONObject("info"),data=root.optJSONObject("movie_data");
        String extension=string(data,"container_extension");if(extension.isEmpty())extension=fallbackExtension;
        return new Detail(describe(info==null?data:info),extension,artwork(info==null?data:info));
    }
    static Series series(String json) throws JSONException{
        JSONObject root=new JSONObject(json),bySeason=root.optJSONObject("episodes");
        if(bySeason==null)throw new JSONException("The provider returned no episode list.");
        Map<String,String> names=new HashMap<>();JSONArray seasonInfo=root.optJSONArray("seasons");
        if(seasonInfo!=null)for(int i=0;i<seasonInfo.length();i++){
            JSONObject s=seasonInfo.optJSONObject(i);if(s!=null)names.put(s.optString("season_number"),string(s,"name"));
        }
        List<Season> seasons=new ArrayList<>();Iterator<String> keys=bySeason.keys();int total=0;
        while(keys.hasNext()){
            String key=keys.next();JSONArray rows=bySeason.optJSONArray(key);if(rows==null)continue;
            List<Episode> episodes=new ArrayList<>();
            for(int i=0;i<rows.length();i++){
                JSONObject e=rows.optJSONObject(i);if(e==null)continue;String id=e.optString("id");if(!id.matches("[0-9]+"))continue;
                int number=e.optInt("episode_num",i+1);String title=string(e,"title","name");if(title.isEmpty())title="Episode "+number;
                String extension=string(e,"container_extension");if(extension.isEmpty())extension="mp4";
                episodes.add(new Episode(id,title,extension,key,number,describe(e.optJSONObject("info")),artwork(e.optJSONObject("info"))));
                if(++total>20000)throw new JSONException("This series exceeds 20,000 episodes.");
            }
            episodes.sort(Comparator.comparingInt(Episode::number));
            String name=names.get(key);if(name==null||name.isEmpty())name=key.equals("0")?"Specials":"Season "+key;
            if(!episodes.isEmpty())seasons.add(new Season(key,name,episodes));
        }
        seasons.sort(Comparator.comparingInt(s->seasonNumber(s.id())));
        return new Series(describe(root.optJSONObject("info")),seasons,artwork(root.optJSONObject("info")));
    }
    private static int seasonNumber(String s){try{return Integer.parseInt(s);}catch(NumberFormatException e){return Integer.MAX_VALUE;}}
}
