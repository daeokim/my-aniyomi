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
        self.assertIn('"UPDATER_ENABLED", "false"', code)
        self.assertIn('personal/signing/signing.properties', code)
        self.assertIn('personal/signing/', (ROOT / '.gitignore').read_text())

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
            self.assertIn(f'overviewPager(GetRemote{cap}.QUERY_POPULAR)', model)
            self.assertIn(f'overviewPager(GetRemote{cap}.QUERY_LATEST)', model)
            self.assertIn('.cachedIn(screenModelScope)', model)
            self.assertIn('overviewMutex.withLock', model)
            self.assertIn('AdaptiveSourceOverview(', ui)
            self.assertIn('overviewLatest.collectOverviewItems()', ui)
            self.assertIn('overviewPopular.collectOverviewItems()', ui)
            self.assertIn('supportsLatest)', ui)
            for action in ['setListing', 'search', 'searchGenre']:
                self.assertRegex(model, rf'fun {action}\([^{{]*\) \{{\s*showOverview = false')

    def test_fold_transition_preserves_anchor_and_image_loading(self):
        viewer = (JAVA / 'tachiyomi/ui/reader/viewer/pager/PagerViewer.kt').read_text()
        adapter = (JAVA / 'tachiyomi/ui/reader/viewer/pager/PagerViewerAdapter.kt').read_text()
        holder = (JAVA / 'tachiyomi/ui/reader/viewer/pager/PagerSpreadHolder.kt').read_text()
        self.assertIn('pager.addOnLayoutChangeListener', viewer)
        self.assertIn('moveToPage(anchor)', viewer)
        self.assertIn('requestedFocus?.takeIf(item::contains)', viewer)
        self.assertIn('FoldLayoutPolicy.group(', adapter)
        self.assertIn('FoldLayoutPolicy.physicalPair(', holder)
        self.assertIn('PagerPageHolder(context, viewer, page)', holder)
        self.assertIn('getPageHolder(spread.second)?.imageLoaded == true', viewer)

    def test_overview_reflows_and_errors_are_per_feed(self):
        ui = (JAVA / 'presentation/browse/AdaptiveSourceOverview.kt').read_text()
        self.assertIn('BoxWithConstraints', ui)
        self.assertIn('FoldLayoutPolicy.useSpread(', ui)
        self.assertIn('horizontalArrangement = Arrangement.spacedBy(12.dp)', ui)
        self.assertIn('verticalArrangement = Arrangement.spacedBy(4.dp)', ui)
        self.assertIn('pagingItems::retry', ui)
        self.assertIn('pagingItems::refresh', ui)
        self.assertIn('val latestScroll = rememberLazyGridState()', ui)
        self.assertIn('val popularScroll = rememberLazyGridState()', ui)
        self.assertIn('state = scrollState', ui)


if __name__ == '__main__':
    unittest.main()
