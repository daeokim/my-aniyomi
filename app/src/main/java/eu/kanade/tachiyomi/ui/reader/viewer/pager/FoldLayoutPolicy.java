package eu.kanade.tachiyomi.ui.reader.viewer.pager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/** Personal fork: window-based layout and stable, chapter-local spread grouping. */
public final class FoldLayoutPolicy {
    private FoldLayoutPolicy() {}

    public static boolean useSpread(float widthDp, float heightDp, boolean enabled, boolean horizontal) {
        // A cover-screen phone rotated sideways must not be mistaken for the inner display.
        return enabled && horizontal && widthDp >= 600 && heightDp >= 480;
    }

    public static <T> List<T> physicalPair(T first, T second, boolean rightToLeft) {
        List<T> pair = new ArrayList<>();
        pair.add(rightToLeft ? second : first);
        pair.add(rightToLeft ? first : second);
        return pair;
    }

    public static <T> List<List<T>> group(
            List<T> items,
            Predicate<T> isPage,
            ToIntFunction<T> index,
            Function<T, Object> chapter,
            Predicate<T> isWide,
            boolean coverAlone) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            T first = items.get(i);
            List<T> spread = new ArrayList<>();
            spread.add(first);
            if (isPage.test(first) && !isWide.test(first) && i + 1 < items.size()) {
                T second = items.get(i + 1);
                int page = index.applyAsInt(first);
                boolean correctSide = coverAlone ? page > 0 && page % 2 == 1 : page % 2 == 0;
                if (correctSide && isPage.test(second) && !isWide.test(second)
                        && index.applyAsInt(second) == page + 1
                        && Objects.equals(chapter.apply(first), chapter.apply(second))) {
                    spread.add(second);
                    i++;
                }
            }
            result.add(spread);
        }
        return result;
    }
}
