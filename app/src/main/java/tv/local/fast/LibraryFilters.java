package tv.local.fast;

import org.json.JSONObject;
import java.util.*;
import java.util.regex.*;

/** Immutable selections can safely travel with background page requests. */
final class LibraryFilters {
    static final String UNKNOWN="Not supplied";
    static final String[] FACETS={"year","genre","language"};
    static final LibraryFilters NONE=new LibraryFilters(Set.of(),Set.of(),Set.of());
    final List<Set<String>> values;
    LibraryFilters(Set<String> years,Set<String> genres,Set<String> languages){
        values=List.of(Set.copyOf(years),Set.copyOf(genres),Set.copyOf(languages));
    }
    int size(){return values.stream().mapToInt(Set::size).sum();}
    private static final Pattern YEAR=Pattern.compile("(?<![0-9])((?:18|19|20)[0-9]{2})(?![0-9])");
    static List<Set<String>> metadata(String name,JSONObject summary){
        Set<String> years=new TreeSet<>(),genres=new TreeSet<>(),languages=new TreeSet<>();
        String date=LibraryData.string(summary,"releasedate","releaseDate","release_date","year");
        Matcher match=YEAR.matcher(date);
        if(match.find())years.add(match.group(1));
        // Only use an explicitly parenthesized year in a title; numbers in titles are ambiguous.
        if(years.isEmpty()){match=Pattern.compile("\\(((?:18|19|20)[0-9]{2})\\)").matcher(name);if(match.find())years.add(match.group(1));}
        split(genres,LibraryData.string(summary,"genre","genres"));
        split(languages,LibraryData.string(summary,"language","original_language","audio_language","languages"));
        for(Set<String> values:List.of(years,genres,languages))if(values.isEmpty())values.add(UNKNOWN);
        return List.of(years,genres,languages);
    }
    private static void split(Set<String> out,String text){
        for(String part:text.split("[,;/|]")){
            String value=part.trim().replaceAll("\\s+"," ");
            if(value.isEmpty()||value.equalsIgnoreCase("null")||value.equals("0"))continue;
            out.add(value.toLowerCase(Locale.ROOT));
        }
    }
    static JSONObject json(String text){try{return new JSONObject(text);}catch(Exception ignored){return new JSONObject();}}
}
