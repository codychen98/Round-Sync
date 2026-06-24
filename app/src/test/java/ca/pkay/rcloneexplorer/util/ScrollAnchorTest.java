package ca.pkay.rcloneexplorer.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ScrollAnchorTest {

  @Test
  public void directoryNavOffset_isStableLegacyValue() {
    assertEquals(10, ScrollAnchor.DIRECTORY_NAV_OFFSET_PX);
  }

  @Test
  public void storesPositionAndOffset() {
    ScrollAnchor anchor = new ScrollAnchor(42, -18);
    assertEquals(42, anchor.position);
    assertEquals(-18, anchor.offset);
  }
}
