package tv.local.fast;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.os.Bundle;
import org.json.JSONObject;
import java.io.StringReader;
import java.util.*;

/** Separate, network-free fixture database; never replaces the owner's library. */
final class FiltersInstrumentation {
    static void run(Instrumentation test){
        Bundle result=new Bundle();String file="filters-test.db";test.getTargetContext().deleteDatabase(file);
        try(Catalog catalog=new Catalog(test.getTargetContext(),file)){
            String json="[{\"stream_id\":1,\"name\":\"First\",\"year\":2024,\"genre\":\"Drama, Comedy\",\"language\":\"en,fr\"},{\"stream_id\":2,\"name\":\"Second\",\"year\":2023,\"genre\":\"Drama\",\"language\":\"fr\"},{\"stream_id\":3,\"name\":\"Third\",\"year\":2022,\"genre\":\"Action\",\"language\":\"en\"},{\"stream_id\":4,\"name\":\"Unknown\"}]";
            LibraryProvider.readItems(catalog.getWritableDatabase(),new StringReader(json),"movie",n->{});
            LibraryProvider.readItems(catalog.getWritableDatabase(),new StringReader(json),"series",n->{});
            LibraryFilters filters=new LibraryFilters(Set.of("2023","2024"),Set.of("drama","comedy"),Set.of("fr"));
            check(catalog.mediaCount("movie","","",filters)==2,"OR within facets; AND across facets; no duplicate titles");
            check(catalog.mediaPage("series","","",0,filters).size()==2,"Series filters");
            check(catalog.mediaCount("movie","","Second",filters)==1,"Filters combine with title search");
            check(catalog.mediaCount("movie","","",new LibraryFilters(Set.of("2024"),Set.of("action"),Set.of()))==0,"Empty intersection");
            check(catalog.mediaCount("movie","","",new LibraryFilters(Set.of(),Set.of(),Set.of(LibraryFilters.UNKNOWN)))==1,"Missing language is selectable");
            check(catalog.mediaFilterOptions("movie").get(1).size()==4,"Distinct genres plus unknown");
            catalog.getWritableDatabase().execSQL("INSERT INTO media_groups VALUES('movie','Other','Provider category')");
            Catalog.indexCategoryGenres(catalog.getWritableDatabase());
            check(catalog.mediaCount("movie","","",new LibraryFilters(Set.of(),Set.of("Category: Provider category"),Set.of()))==1,"Missing genres use explicitly labeled provider categories");
            catalog.getWritableDatabase().execSQL("UPDATE media_items SET category='kept' WHERE id='3'");
            check(catalog.mediaCount("movie","kept","",filters)==1,"All-only filters do not restrict other categories");
            // Reopen an actual v4 schema, preserving catalog and progress while backfilling metadata.
            catalog.savePlayback("movie:1",123000,600000);
            catalog.getWritableDatabase().execSQL("DROP TABLE media_facets");catalog.getWritableDatabase().setVersion(4);
        }catch(Throwable e){result.putString("failure",e.toString());test.finish(1,result);return;}
        try(Catalog migrated=new Catalog(test.getTargetContext(),file)){
            check(migrated.mediaCount("movie","","",new LibraryFilters(Set.of("2024"),Set.of(),Set.of("fr")))==1,"v4 metadata migration");
            check(migrated.playbackPosition("movie:1")==123000,"Migration keeps progress");
            result.putBoolean("combined_filters_and_migration",true);
            test.finish(-1,result);
        }catch(Throwable e){result.putString("failure",e.toString());test.finish(1,result);}
        finally{test.getTargetContext().deleteDatabase(file);}
    }
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
}
