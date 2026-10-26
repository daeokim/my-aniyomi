"""Static integration contracts; these are not substitutes for an Android build or device tests."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / 'app/src/main/java/eu/kanade'
ANDROID = '{http://schemas.android.com/apk/res/android}'


class IntegrationContracts(unittest.TestCase):
    def test_replaces_original_package_for_all_variants(self):
        code = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('applicationId = "xyz.jmir.tachiyomi.mi"', code)
        self.assertNotIn('applicationIdSuffix', code)
        # Updates come from this fork's own releases, never the official Aniyomi repository.
        self.assertIn('"UPDATER_ENABLED", "true"', code)
        updater = (JAVA / 'tachiyomi/data/updater/AppUpdateChecker.kt').read_text()
        self.assertIn('"daeokim/my-aniyomi"', updater)
        self.assertNotIn('"aniyomiorg/aniyomi', updater)
        self.assertIn('personal/signing/signing.properties', code)
        self.assertIn('personal/signing/', (ROOT / '.gitignore').read_text())

    def test_torrserver_is_not_shipped_and_cannot_be_started(self):
        # The native library is excluded; every TorrServer start is gated on a key no screen can turn on.
        self.assertIn('excludes += "**/libtorrserver.so"', (ROOT / 'app/build.gradle.kts').read_text())
        prefs = (ROOT / 'core/common/src/main/java/aniyomi/core/common/torrent/TorrentPreferences.kt').read_text()
        self.assertIn('getBoolean("dtoon_torrserver_unavailable", false)', prefs)
        settings = JAVA / 'presentation/more/settings/screen'
        self.assertNotIn('PlayerSettingsTorrentScreen', (settings / 'player/PlayerSettingsMainScreen.kt').read_text())
        self.assertNotIn('PlayerSettingsTorrentScreen', (settings / 'SettingsSearchScreen.kt').read_text())

    def test_private_branding_and_resizable_reader(self):
        app = ET.parse(ROOT / 'app/src/main/AndroidManifest.xml').getroot().find('application')
        self.assertEqual(app.get(ANDROID + 'label'), '@string/personal_app_name')
        self.assertEqual(app.get(ANDROID + 'resizeableActivity'), 'true')
        reader = next(a for a in app.findall('activity') if a.get(ANDROID + 'name') == '.ui.reader.ReaderActivity')
        self.assertEqual(reader.get(ANDROID + 'resizeableActivity'), 'true')
        for path in ['values/personal.xml', 'drawable/ic_personal_foreground.xml', 'mipmap-anydpi-v26/ic_personal_launcher.xml']:
            ET.parse(ROOT / 'app/src/main/res' / path)

    def test_both_catalogs_reach_real_latest_and_popular_queries(self):
        for kind in ['anime', 'manga']:
            cap = kind.title()
            folder = JAVA / f'tachiyomi/ui/browse/{kind}/source/browse'
            model = (folder / f'Browse{cap}SourceScreenModel.kt').read_text()
            ui = (folder / f'Browse{cap}SourceScreen.kt').read_text()
            self.assertIn(f'GetRemote{cap}.QUERY_POPULAR', model)
            self.assertIn(f'GetRemote{cap}.QUERY_LATEST', model)
            self.assertIn('catalogMutex.withLock', model)
            self.assertIn('SourceCatalog(', ui)
            self.assertNotIn('AdaptiveSourceOverview', ui)
            self.assertIn('itemsFor = screenModel::catalogItems', ui)

    def test_fold_transition_preserves_anchor_and_image_loading(self):
        viewer = (JAVA / 'tachiyomi/ui/reader/viewer/pager/PagerViewer.kt').read_text()
        adapter = (JAVA / 'tachiyomi/ui/reader/viewer/pager/PagerViewerAdapter.kt').read_text()
        holder = (JAVA / 'tachiyomi/ui/reader/viewer/pager/PagerSpreadHolder.kt').read_text()
        self.assertIn('pager.addOnLayoutChangeListener', viewer)
        self.assertIn('restorePosition(previous, sameChapter, chapters)', viewer)
        # Without a usable anchor the pager goes to the current chapter's page, never the stale index.
        self.assertIn('moveToPage(pages[chapters.currChapter.requestedPage.coerceIn(0, pages.lastIndex)], smoothScroll = false)', viewer)
        self.assertIn('(awaitingIdleViewerChapters ?: viewerChapters)?.let(::setChapters)', viewer)
        self.assertIn('ReaderPositionPolicy.spreadFocus(requestedFocus, currentPage as? ReaderPage, item.first, item::contains)', viewer)
        self.assertIn('pager.setSaveFromParentEnabled(false)', viewer)
        self.assertIn('activity.viewModel.onPageRendered(spread.second)', viewer)
        self.assertIn('FoldLayoutPolicy.group(', adapter)
        self.assertIn('FoldLayoutPolicy.physicalPair(', holder)
        self.assertIn('PagerPageHolder(context, viewer, page)', holder)
        self.assertIn('getPageHolder(spread.second)?.imageLoaded == true', viewer)

    def test_fold_recreation_keeps_reading_chapter(self):
        # Any layout delta (including non-zero) is ignored until actual user navigation.
        webtoon = (JAVA / 'tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt').read_text()
        self.assertIn('if (viewport.isRestoring) return', webtoon)
        self.assertIn('if (newState == RecyclerView.SCROLL_STATE_DRAGGING) viewport.beginUserScroll()', webtoon)
        self.assertIn('recycler.setSaveFromParentEnabled(false)', webtoon)
        self.assertIn('layoutManager.findFirstVisibleItemPosition()', webtoon)
        self.assertIn('val offset = anchor.pixelOffset(child?.height ?: 0)', webtoon)
        holder = (JAVA / 'tachiyomi/ui/reader/viewer/webtoon/WebtoonPageHolder.kt').read_text()
        self.assertIn('page?.let(viewer::onPageLayout)', holder)
        reader = (JAVA / 'tachiyomi/ui/reader/ReaderActivity.kt').read_text()
        self.assertIn('intent.putExtra("chapter", it)', reader)
        self.assertIn('moveToPageIndex(if (chapter.read) 0 else chapter.last_page_read)', reader)
        self.assertIn('viewModel.state.value.viewer?.saveViewport()', reader)

    def test_shared_catalog_reflows_and_keeps_independent_query_state(self):
        ui = (JAVA / 'presentation/browse/catalog/SourceCatalog.kt').read_text()
        self.assertIn('HorizontalPager', ui)
        self.assertIn('GridCells.Adaptive', ui)
        self.assertIn('holder.SaveableStateProvider(queryKey)', ui)
        self.assertIn('items::retry', ui)
        self.assertIn('items::refresh', ui)
        self.assertIn('grid_anchor:', ui)
        self.assertNotIn('LazyRow', ui)
        self.assertFalse((JAVA / 'presentation/browse/AdaptiveSourceOverview.kt').exists())


if __name__ == '__main__':
    unittest.main()
