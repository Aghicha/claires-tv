package tv.local.fast;

import org.json.JSONObject;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class Source {
    final String type, endpoint, user, password;
    Source(String type, String endpoint, String user, String password) {
        this.type=type; this.endpoint=normalize(endpoint); this.user=user.trim(); this.password=password;
        if (!type.equals("xtream") && !type.equals("m3u")) throw new IllegalArgumentException("Choose a supported source type.");
        if (type.equals("xtream") && (this.user.isEmpty() || password.isEmpty())) throw new IllegalArgumentException("Enter your provider username and password.");
        if (type.equals("xtream") && (URI.create(this.endpoint).getQuery()!=null || URI.create(this.endpoint).getFragment()!=null))
            throw new IllegalArgumentException("Enter the server address, without a playlist or query string.");
    }
    static String normalize(String input) {
        String s=input.trim();
        if (!s.contains("://")) s="https://"+s;
        URI u;
        try { u=URI.create(s); } catch (RuntimeException e) { throw new IllegalArgumentException("Enter a valid HTTP or HTTPS address."); }
        if (!("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme())) || u.getHost()==null || u.getUserInfo()!=null)
            throw new IllegalArgumentException("Enter an HTTP or HTTPS server address without embedded user information.");
        return s;
    }
    String base() { String b=endpoint; while(b.endsWith("/")) b=b.substring(0,b.length()-1); return b; }
    static String enc(String s) {
        try {return URLEncoder.encode(s,"UTF-8").replace("+", "%20");}
        catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);}
    }
    String api(String action) { return base()+"/player_api.php?username="+enc(user)+"&password="+enc(password)+"&action="+action; }
    String play(Channel c) { return type.equals("m3u") ? c.url : base()+"/live/"+enc(user)+"/"+enc(password)+"/"+enc(c.id)+".ts"; }
    String playHls(Channel c) { return type.equals("m3u") ? c.url : base()+"/live/"+enc(user)+"/"+enc(password)+"/"+enc(c.id)+".m3u8"; }
    String playLibrary(String kind,String id,String extension){
        if(!type.equals("xtream"))throw new IllegalArgumentException("Films and Series require an Xtream source.");
        if(!kind.equals("movie")&&!kind.equals("episode"))throw new IllegalArgumentException("Unknown playback type.");
        if(!id.matches("[0-9]+"))throw new IllegalArgumentException("Invalid film or episode ID.");
        String ext=extension==null?"":extension.toLowerCase(java.util.Locale.ROOT).replaceFirst("^\\.","");
        if(!ext.matches("[a-z0-9]{1,10}"))ext="mp4";
        return base()+"/"+(kind.equals("movie")?"movie":"series")+"/"+enc(user)+"/"+enc(password)+"/"+id+"."+ext;
    }
    String json() throws Exception { return new JSONObject().put("type",type).put("endpoint",endpoint).put("user",user).put("password",password).toString(); }
    static Source fromJson(String s) throws Exception { JSONObject j=new JSONObject(s); return new Source(j.getString("type"),j.getString("endpoint"),j.optString("user"),j.optString("password")); }
}
