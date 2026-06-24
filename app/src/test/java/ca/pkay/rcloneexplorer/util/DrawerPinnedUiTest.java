package ca.pkay.rcloneexplorer.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import ca.pkay.rcloneexplorer.Items.PinnedItem;

public class DrawerPinnedUiTest {

    @Test
    public void drawerVisiblePins_returnsAllWhenAtOrBelowCap() {
        List<PinnedItem> pins = Arrays.asList(
                new PinnedItem("remote1", "", null),
                new PinnedItem("remote2", "folder", null)
        );

        assertEquals(2, DrawerPinnedUi.drawerVisiblePins(pins).size());
        assertFalse(DrawerPinnedUi.hasDrawerOverflow(pins));
    }

    @Test
    public void drawerVisiblePins_returnsFirstFourWhenOverflow() {
        List<PinnedItem> pins = Arrays.asList(
                new PinnedItem("remote1", "", null),
                new PinnedItem("remote2", "", null),
                new PinnedItem("remote3", "", null),
                new PinnedItem("remote4", "", null),
                new PinnedItem("remote5", "", null),
                new PinnedItem("remote6", "", null)
        );

        List<PinnedItem> visible = DrawerPinnedUi.drawerVisiblePins(pins);
        assertEquals(DrawerPinnedUi.DRAWER_PINNED_VISIBLE_MAX, visible.size());
        assertEquals("remote1", visible.get(0).getRemoteName());
        assertEquals("remote4", visible.get(3).getRemoteName());
        assertTrue(DrawerPinnedUi.hasDrawerOverflow(pins));
    }

    @Test
    public void drawerVisiblePins_emptyList() {
        assertTrue(DrawerPinnedUi.drawerVisiblePins(Collections.emptyList()).isEmpty());
        assertFalse(DrawerPinnedUi.hasDrawerOverflow(Collections.emptyList()));
    }
}
