package tv.local.fast;

import org.junit.Test;
import org.json.JSONObject;
import java.util.*;
import static org.junit.Assert.*;

public class LibraryFiltersTest {
    @Test public void separatesAndNormalizesMultipleMetadataValues() throws Exception{
        var values=LibraryFilters.metadata("Film",new JSONObject("{\"release_date\":\"2023-05-01\",\"genre\":\"Drama / Comedy, drama\",\"language\":\"EN; fr\"}"));
        assertEquals(Set.of("2023"),values.get(0));assertEquals(Set.of("drama","comedy"),values.get(1));assertEquals(Set.of("en","fr"),values.get(2));
    }
    @Test public void missingFieldsStayUnknownRatherThanGuessing(){
        var values=LibraryFilters.metadata("2001 A Space Odyssey",new JSONObject());
        for(var facet:values)assertEquals(Set.of(LibraryFilters.UNKNOWN),facet);
        assertEquals(Set.of("1968"),LibraryFilters.metadata("2001 A Space Odyssey (1968)",new JSONObject()).get(0));
    }
    @Test public void providerReleaseOverridesTitleYear() throws Exception{
        assertEquals(Set.of("2024"),LibraryFilters.metadata("Film (2020)",new JSONObject("{\"year\":2024}")).get(0));
    }
    @Test public void selectionsAreImmutableSnapshots(){
        Set<String> years=new HashSet<>(Set.of("2024"));LibraryFilters snapshot=new LibraryFilters(years,Set.of(),Set.of());years.clear();assertEquals(1,snapshot.size());
    }
}
