# better modules: notes

what was missing in the module system when this branch started, and what was done about it.

## what a module page could do before

a page with `replaces` could stand in for any route of the app (every route goes through `ModuleTakeover`), but what it got to show was small:

- artist page: name, listeners, photo and the top songs. nothing else
- artist subpages (`artist/songs`, `artist/albums`, `artist/similar`, `artist/items`): the route arguments only
- a `when` only worked on a whole page, never on one block
- no way to put blocks side by side
- a block type that mochy did not know vanished without a word

## done in this branch

| gap | what was added |
| --- | --- |
| artist banner and name logo not reachable | `cover` has `source`: `cover`, `banner`, `logo` |
| artist shelves (albums, singles, similar artists, featured on...) not reachable | `albums`, `artists` and `shelf` blocks, fed by every shelf of the artist page |
| artist bio not reachable | `bio` block, a few lines first and all of it on a tap |
| no follow, library heart, radio or share on a taken over page | `follow`, `like`, `radio`, `share` buttons |
| artist subpages had no subject | `artist/songs`, `artist/albums`, `artist/similar` and `artist/items` get the same artist as `artist` |
| `when` only on pages | any block can carry `when` (`"key"`, `"!key"`, `"key=value"`) |
| no layout inside a page | `row` block: blocks side by side sharing the width |
| unknown block types dropped silently | they are dropped as before and now leave a log line |
| wiki did not cover any of it | `docs/wiki/index.html` taking over pages section, with an artist page example |
| no test for it | `SkinParserTest` reads an artist page with every new block |

files touched: `modules/skin/ModuleSkin.kt`, `modules/skin/SkinParser.kt`, `ui/screens/settings/modules/PageSubjects.kt`, `ModulePageBlocks.kt`, `ModulePageScreen.kt`, `res/values/modules_strings.xml`.

## still open

- the other pages (home, library tabs, search, history, stats) still only get their route arguments as the subject. the artist page is the first one with a full subject
- pieces inside a page that are not routes (bottom sheets, dialogs, the long press menus) cannot be replaced by a module
- the new strings only exist in `values/`. `values-tr` and the other translations were left alone on purpose, the app falls back to english until someone translates them
- the `songs` block has no sort or filter and `shelf` has no see all button
- nothing here was compiled on the machine that wrote it (no android sdk there). the build.yml run on the branch is the first real compile
