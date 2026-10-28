package tv.local.fast;

import java.io.BufferedReader;
import java.io.Reader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

final class Playlist {
    interface Sink { void add(Channel channel) throws Exception; }
    private static final Pattern GROUP=Pattern.compile("(?:^|\\s)group-title=\"([^\"]*)\"");
    static int parse(Reader input, String base, Sink sink) throws Exception {
        BufferedReader r=new BufferedReader(input); String line,name="",group="Other"; int count=0; boolean header=false;
        while((line=r.readLine())!=null) {
            if(Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            if(line.length()>65536) throw new java.io.IOException("Playlist line is too long.");
            line=line.replace("\uFEFF", "").trim();
            if(line.isEmpty()) continue;
            if(!header) { if(!line.startsWith("#EXTM3U")) throw new java.io.IOException("The address did not return an M3U playlist."); header=true; }
            if(line.startsWith("#EXT-X-")) throw new java.io.IOException("This is an HLS stream, not a channel playlist. Enter your provider's M3U channel-list URL.");
            if(line.startsWith("#EXTINF:")) {
                boolean quoted=false; int comma=-1;
                for(int i=0;i<line.length();i++) { char ch=line.charAt(i); if(ch=='"') quoted=!quoted; if(ch==','&&!quoted) { comma=i; break; } }
                name=comma<0 ? "" : line.substring(comma+1).trim();
                var m=GROUP.matcher(line); group=m.find()?m.group(1):"Other";
            } else if(line.startsWith("#EXTGRP:")) group=line.substring(8).trim();
            else if(!line.startsWith("#")) {
                String url;
                try { url=Source.normalize(URI.create(base).resolve(line).toString()); } catch(RuntimeException e) { name=""; continue; }
                if(name.isEmpty()) name="Channel "+(count+1);
                byte[] digest=MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8));
                StringBuilder id=new StringBuilder("m"); for(byte b:digest) id.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                sink.add(new Channel(id.toString(),name,group,url,false)); count++; name=""; group="Other";
            }
            if(count>200000) throw new java.io.IOException("Playlist exceeds 200,000 channels.");
        }
        return count;
    }
}
