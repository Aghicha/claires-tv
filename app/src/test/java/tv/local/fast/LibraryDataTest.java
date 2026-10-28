package tv.local.fast;

import org.junit.Test;
import org.json.JSONException;
import static org.junit.Assert.*;

public class LibraryDataTest {
    @Test public void movieDetailsUseProviderContainerAndPlot() throws Exception{
        LibraryData.Detail detail=LibraryData.movie("{\"info\":{\"plot\":\"A short story.\",\"genre\":\"Drama\",\"releasedate\":\"2025-01-01\",\"duration\":\"01:30:00\"},\"movie_data\":{\"container_extension\":\"mkv\"}}","mp4");
        assertEquals("mkv",detail.extension());assertTrue(detail.description().contains("A short story."));assertTrue(detail.description().contains("Drama"));assertTrue(detail.description().contains("01:30:00"));
    }
    @Test public void missingMovieInfoIsUsable() throws Exception{assertEquals("mp4",LibraryData.movie("{}","mp4").extension());assertTrue(LibraryData.movie("{}","mp4").description().contains("No additional"));}
    @Test public void seasonsAndEpisodesSortNumericallyAndKeepSpecials() throws Exception{
        String json="{\"info\":{\"plot\":\"Series story\"},\"seasons\":[{\"season_number\":2,\"name\":\"Second season\"}],\"episodes\":{\"10\":[{\"id\":100,\"episode_num\":1}],\"2\":[{\"id\":22,\"episode_num\":2,\"title\":\"Second\",\"container_extension\":\"mkv\"},{\"id\":21,\"episode_num\":1,\"title\":\"First\"}],\"0\":[{\"id\":1,\"episode_num\":0,\"title\":\"Special\"}]}}";
        var data=LibraryData.series(json);assertEquals("0",data.seasons().get(0).id());assertEquals("Second season",data.seasons().get(1).name());assertEquals("10",data.seasons().get(2).id());assertEquals("21",data.seasons().get(1).episodes().get(0).id());assertEquals("mkv",data.seasons().get(1).episodes().get(1).extension());
    }
    @Test public void malformedIdsAndEmptySeasonsAreExcluded() throws Exception{
        var data=LibraryData.series("{\"episodes\":{\"1\":[{\"id\":\"../bad\"},{\"id\":42}],\"2\":[]}}");assertEquals(1,data.seasons().size());assertEquals("42",data.seasons().get(0).episodes().get(0).id());assertEquals("mp4",data.seasons().get(0).episodes().get(0).extension());
    }
    @Test(expected=JSONException.class) public void rejectsMissingEpisodeObject() throws Exception{LibraryData.series("{\"error\":\"not available\"}");}
    @Test public void supportsProviderUnicodeAndNullFields() throws Exception{
        var data=LibraryData.series("{\"episodes\":{\"1\":[{\"id\":9,\"title\":\"Épisode 日本語\",\"info\":{\"plot\":null,\"duration\":\"00:42:00\"}}]}}");assertEquals("Épisode 日本語",data.seasons().get(0).episodes().get(0).title());assertTrue(data.seasons().get(0).episodes().get(0).summary().contains("00:42:00"));
    }
}
