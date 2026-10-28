# Claire's TV interface

Mode: Operate. A remote-controlled channel browser used across a room, often in dim light. Dark green surfaces reduce glare; pale mint identifies keyboard/D-pad focus with a high-contrast outline. System sans typography, 15sp channel rows, 14sp categories, 13sp guide text, and a 22sp app title, chosen for density. No decorative motion or network images.

The browsing surface allocates 27% to categories, 46% to titles, and 27% to information. The header has Live TV, Films, and Series navigation. Live refresh/source settings sit behind Settings; title search sits above the center list. Category search is independent of title search. A long press on a category clears the category filter and reveals that item within the full ordering; Up/Down moves it, OK saves, and Back cancels. Saved category order survives restarts and ordinary catalog refreshes. Rows load in bounded background pages behind one continuous list; no page buttons or permanent status/help lines consume screen space.

Brand: Claire's TV. A pale mint logo tile contains a forest-green television frame and C, with an ochre-gold play mark. The TV launcher banner uses a warm cream background and forest-green lettering. Type is converted to vector paths so no font dependency is bundled.

The enlarged header mark uses a 104-unit vector viewport matching its 104-pixel size on the target TV. Its outline uses whole coordinates and even-width strokes, with no nested scaling group. This preserves the larger appearance while aligning straight edges to the pixel grid.

The live browser's Information column is read only: the guide and the two actions that used to sit under it
are gone, so the column holds nothing focusable and Right from the categories reaches the channels instead of
stopping in the guide. Playing is OK on a channel and favoriting is a long press, which is where the hand
already is.

Playback names what is on screen without covering it: a slim translucent strip across the top carrying the
channel or title, shown on start and on every channel change, gone after four seconds. It is a strip, not a
scrim — the picture stays visible behind it. Connection state belongs somewhere it can be ignored, so
fetching, retrying and failure messages run along the bottom in small type rather than over the image.

Playback shows unobstructed video, with controller buttons and their scrim disabled. Up/Down selects the previous/next channel within the current category and channel search, wrapping at the ends. There is no loading/title overlay; failures alone show a recovery message. Setup uses explicitly labeled native fields. Back leaves playback for the browser.

Films and Series browse differently from Live TV. Live TV keeps its three text columns, because a channel is
chosen by name and its guide must stay on screen. A film is chosen by its artwork, so the library is two
sections: categories on the left at 26%, and a poster grid filling the remaining 74%, five columns wide, each
cell a poster above a two-line title. Opening a title replaces that browser with its own page — large artwork,
title, released/genre/rating/duration, plot, cast and director, and a Watch button that holds focus so OK
plays immediately. A series page continues below with its seasons as a row of buttons and the chosen season's
episodes beneath them. Back returns to the grid at the cell it was opened from.

Posters are bounded on purpose: decoded straight to the cell size in RGB_565, at most 16 MB in memory and 600
files on disk, three download threads at minimum priority. A title the provider gives no artwork for keeps a
plain placeholder rather than a gap, and a poster that arrives after its cell has been recycled is discarded.

**Continue watching** is a virtual category beside All and Favorites, built from saved playback positions
rather than from anything the provider sends: a film matches its own progress key, a series matches any of its
episodes'. It is ordered by when each title was last watched, not by catalog position, because the thing you
stopped yesterday should be the first poster you see. Finishing a title removes it without a separate "mark
watched" action, since a position within a minute of the end is already stored as zero.

Categories are listed by name, and a category the provider left unnamed appears as `Category <id>` after every named one rather than sorting its bare number to the top. Switching library clears the previous library's categories instead of leaving them under the new heading. When an import fails, the status line keeps the provider's own reason, since the information panel is repainted by the saved catalog that replaces it.

Films and episodes also open without a controller overlay. OK reveals on-demand playback controls, Left/Right seek when controls are hidden, and the media Play/Pause key pauses or resumes. Back hides visible controls before returning to the library. Films show metadata in the information panel; opening a series replaces categories with seasons and titles with episodes. No posters or artwork downloads compete with catalog or playback bandwidth.
