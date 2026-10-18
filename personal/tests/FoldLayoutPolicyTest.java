import eu.kanade.tachiyomi.ui.reader.viewer.pager.FoldLayoutPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Runs the production policy on a plain JDK, without replacing it with a test implementation. */
public class FoldLayoutPolicyTest {
    record Page(int chapter, int index, boolean wide) {}
    private static int checks;
    static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    static List<List<Object>> group(List<Object> items, boolean cover) {
        return FoldLayoutPolicy.group(items, x -> x instanceof Page, x -> ((Page) x).index(),
            x -> ((Page) x).chapter(), x -> ((Page) x).wide(), cover);
    }
    static void verify(List<Object> items, boolean cover) {
        List<List<Object>> groups = group(items, cover);
        List<Object> flattened = new ArrayList<>();
        for (List<Object> pair : groups) {
            check(pair.size() == 1 || pair.size() == 2, "spread cardinality");
            flattened.addAll(pair);
            if (pair.size() == 2) {
                Page a = (Page) pair.get(0), b = (Page) pair.get(1);
                check(a.chapter() == b.chapter(), "chapter boundary crossed");
                check(a.index() + 1 == b.index(), "nonconsecutive pages");
                check(!a.wide() && !b.wide(), "wide scan paired");
                check(cover ? a.index() > 0 && a.index() % 2 == 1 : a.index() % 2 == 0,
                    "unstable pairing parity");
                check(FoldLayoutPolicy.physicalPair(a, b, false).equals(pair), "LTR physical order");
                check(FoldLayoutPolicy.physicalPair(a, b, true).equals(List.of(b, a)), "RTL physical order");
            }
        }
        check(flattened.equals(items), "page lost, duplicated or reordered");
        // RTL reverses spread order in the adapter; walking right-to-left recovers reading order.
        List<List<Object>> rtl = new ArrayList<>(groups);
        Collections.reverse(rtl);
        List<Object> readingOrder = new ArrayList<>();
        for (int i = rtl.size() - 1; i >= 0; i--) readingOrder.addAll(rtl.get(i));
        check(readingOrder.equals(items), "RTL chapter traversal");
    }
    public static void main(String[] args) {
        check(!FoldLayoutPolicy.useSpread(360, 780, true, true), "folded portrait");
        check(!FoldLayoutPolicy.useSpread(780, 360, true, true), "folded landscape");
        check(FoldLayoutPolicy.useSpread(700, 820, true, true), "unfolded portrait");
        check(FoldLayoutPolicy.useSpread(820, 700, true, true), "unfolded landscape");
        check(!FoldLayoutPolicy.useSpread(599, 800, true, true), "split-window below threshold");
        check(FoldLayoutPolicy.useSpread(600, 480, true, true), "inclusive threshold");
        check(!FoldLayoutPolicy.useSpread(700, 820, false, true), "user disabled spreads");
        check(!FoldLayoutPolicy.useSpread(700, 820, true, false), "vertical/webtoon exclusion");
        verify(List.of(), true);
        check(group(List.of(new Page(1, 0, false)), true).size() == 1, "single-page chapter");
        List<Object> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) five.add(new Page(1, i, false));
        check(group(five, true).stream().map(List::size).toList().equals(List.of(1, 2, 2)), "cover alone");
        check(group(five, false).stream().map(List::size).toList().equals(List.of(2, 2, 1)), "pairing shift");
        verify(five, true); verify(five, false);
        verify(List.of(new Page(0, 9, false), "transition", new Page(1, 0, false), new Page(1, 1, false)), true);
        verify(List.of(new Page(1, 1, false), new Page(2, 2, false)), true);
        verify(List.of(new Page(1, 1, false), new Page(1, 3, false)), true);
        verify(List.of(new Page(1, 1, true), new Page(1, 2, false), new Page(1, 3, false)), true);
        Random random = new Random(20261019L);
        for (int run = 0; run < 500; run++) {
            List<Object> items = new ArrayList<>();
            for (int chapter = 0; chapter < 3; chapter++) {
                for (int i = 0, count = random.nextInt(45); i < count; i++) {
                    items.add(new Page(chapter, i, random.nextInt(8) == 0));
                }
                items.add("transition-" + chapter);
            }
            verify(items, true); verify(items, false);
            // Adjacent-chapter preload windows must preserve absolute page parity.
            if (items.size() > 4) verify(items.subList(items.size() - 4, items.size()), true);
        }
        System.out.println("FoldLayoutPolicy: " + checks + " assertions passed (production Java compiled).");
    }
}
