package ca.pkay.rcloneexplorer.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

import ca.pkay.rcloneexplorer.Items.PinnedItem;
import ca.pkay.rcloneexplorer.Items.RemoteItem;

public final class DrawerPinnedUi {

    public static final int DRAWER_PINNED_VISIBLE_MAX = 4;

    private DrawerPinnedUi() {
    }

    @NonNull
    public static String resolveDrawerLabel(
            @NonNull PinnedItem pinnedItem,
            @Nullable RemoteItem remoteItem
    ) {
        if (remoteItem != null
                && pinnedItem.getPath().isEmpty()
                && pinnedItem.getDisplayLabel() == null
                && remoteItem.getDisplayName() != null
                && !remoteItem.getDisplayName().equals(pinnedItem.getRemoteName())) {
            return remoteItem.getDisplayName();
        }
        return pinnedItem.getEffectiveLabel();
    }

    @NonNull
    public static List<PinnedItem> drawerVisiblePins(@NonNull List<PinnedItem> allPins) {
        if (allPins.size() <= DRAWER_PINNED_VISIBLE_MAX) {
            return allPins;
        }
        return Collections.unmodifiableList(allPins.subList(0, DRAWER_PINNED_VISIBLE_MAX));
    }

    public static boolean hasDrawerOverflow(@NonNull List<PinnedItem> allPins) {
        return allPins.size() > DRAWER_PINNED_VISIBLE_MAX;
    }
}
