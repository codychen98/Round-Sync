package ca.pkay.rcloneexplorer.util;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/**
 * RecyclerView scroll position for a directory path: first visible adapter index and its top offset.
 */
public final class ScrollAnchor {

  /** Offset used when restoring scroll after navigating into a child folder (legacy behavior). */
  public static final int DIRECTORY_NAV_OFFSET_PX = 10;

  public final int position;
  public final int offset;

  public ScrollAnchor(int position, int offset) {
    this.position = position;
    this.offset = offset;
  }

  @NonNull
  public static ScrollAnchor captureFrom(@NonNull LinearLayoutManager layoutManager) {
    int position = layoutManager.findFirstVisibleItemPosition();
    if (position == RecyclerView.NO_POSITION) {
      return new ScrollAnchor(0, 0);
    }
    View child = layoutManager.findViewByPosition(position);
    int offset = child != null ? child.getTop() : 0;
    return new ScrollAnchor(position, offset);
  }
}
