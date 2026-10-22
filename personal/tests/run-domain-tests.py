#!/usr/bin/env python3
"""Compile real production rules plus JUnit fixtures without requiring Android SDK.
The transport and annotation shims cannot service API calls or exercise Android UI.
JVM_DEPS_DIR points to the Kotlin 2.4.0 compiler, coroutines 1.10.2,
serialization 1.9.0 and JUnit Platform 1.11.4 jars; no credentials are used.
"""
import os, pathlib, subprocess
root=pathlib.Path(__file__).resolve().parents[2]
deps=pathlib.Path(os.environ['JVM_DEPS_DIR']).resolve()
out=root/'personal/test-output/domain';out.mkdir(parents=True,exist_ok=True)
cp=os.pathsep.join(str(p) for p in deps.glob('*.jar'))
app=root/'app/src/main/java'
files=[app/p for p in [
 'eu/kanade/tachiyomi/data/recommendations/RelatedMangaRepository.kt',
 'eu/kanade/tachiyomi/data/recommendations/GenreMatching.kt',
 'eu/kanade/tachiyomi/data/personal/EpisodeMapping.kt',
 'eu/kanade/tachiyomi/util/system/ConnectionRecovery.kt',
 'eu/kanade/tachiyomi/util/system/ConnectionSignals.kt',
 'eu/kanade/tachiyomi/ui/storage/StorageCalculation.kt',
 'eu/kanade/presentation/browse/catalog/CatalogPolicy.kt',
 'eu/kanade/presentation/browse/catalog/MangaCatalogFilters.kt',
 'eu/kanade/presentation/browse/catalog/AnimeCatalogFilters.kt',
]]
files.append(root/'core/common/src/main/java/eu/kanade/tachiyomi/network/HttpException.kt')
api=root/'source-api/src/commonMain/kotlin/eu/kanade/tachiyomi'
files += [api/p for p in ['source/model/Filter.kt','source/model/FilterList.kt','animesource/model/AnimeFilter.kt','animesource/model/AnimeFilterList.kt']]
files += list((root/'personal/tests/jvm-support').glob('*.kt'))
tests=root/'app/src/test/java'
files += [tests/p for p in [
 'eu/kanade/tachiyomi/data/recommendations/RelatedMangaRepositoryTest.kt',
 'eu/kanade/tachiyomi/data/recommendations/GenreMatchingTest.kt',
 'eu/kanade/tachiyomi/data/personal/EpisodeMappingTest.kt',
 'eu/kanade/tachiyomi/util/system/ConnectionRecoveryTest.kt',
 'eu/kanade/tachiyomi/ui/storage/StorageCalculationTest.kt',
 'eu/kanade/presentation/browse/catalog/CatalogSortTest.kt',
]]
args=['java','-Xmx1g','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-classpath',cp,'-Xplugin='+str(deps/'kotlin-serialization-compiler-plugin-embeddable-2.4.0.jar'),'-d',str(out)]+list(map(str,files))
subprocess.run(args,check=True)
subprocess.run(['java','-Xmx1g','-cp',str(out)+os.pathsep+cp,'org.junit.platform.console.ConsoleLauncher','execute','--scan-class-path='+str(out),'--disable-ansi-colors','--details=tree','--reports-dir='+str(root/'personal/test-output/junit')],check=True)
