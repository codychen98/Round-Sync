package ca.pkay.rcloneexplorer.Dialogs;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ca.pkay.rcloneexplorer.Items.PinnedItem;
import ca.pkay.rcloneexplorer.Items.PinnedItemStore;
import ca.pkay.rcloneexplorer.Items.RemoteItem;
import ca.pkay.rcloneexplorer.R;
import ca.pkay.rcloneexplorer.Rclone;
import ca.pkay.rcloneexplorer.util.DrawerPinnedUi;

public class PinnedItemsPickerDialogFragment extends DialogFragment {

    public interface OnPinnedItemSelectedListener {
        void onPinnedItemSelected(PinnedItem pinnedItem);
    }

    private Context context;
    private OnPinnedItemSelectedListener listener;

    public static PinnedItemsPickerDialogFragment newInstance() {
        return new PinnedItemsPickerDialogFragment();
    }

    public void setOnPinnedItemSelectedListener(OnPinnedItemSelectedListener listener) {
        this.listener = listener;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        this.context = context;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        List<PinnedItem> items = PinnedItemStore.load(context);
        HashMap<String, RemoteItem> remoteByName = buildRemoteByName();

        LayoutInflater inflater = ((FragmentActivity) context).getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_edit_pinned_items, null);

        RecyclerView recyclerView = view.findViewById(R.id.recycler_pinned_items);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(new PinPickerAdapter(items, remoteByName));

        return new MaterialAlertDialogBuilder(context, R.style.RoundedCornersDialog)
                .setTitle(R.string.nav_drawer_pinned_header)
                .setView(view)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }

    @NonNull
    private HashMap<String, RemoteItem> buildRemoteByName() {
        HashMap<String, RemoteItem> remoteByName = new HashMap<>();
        Rclone rclone = new Rclone(context);
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> renamedRemotes = sharedPreferences.getStringSet(
                getString(R.string.pref_key_renamed_remotes), new HashSet<>());

        for (RemoteItem item : rclone.getRemotes()) {
            if (renamedRemotes.contains(item.getName())) {
                String displayName = sharedPreferences.getString(
                        getString(R.string.pref_key_renamed_remote_prefix, item.getName()),
                        item.getName());
                item.setDisplayName(displayName);
            }
            remoteByName.put(item.getName(), item);
        }
        return remoteByName;
    }

    private class PinPickerAdapter extends RecyclerView.Adapter<PinPickerAdapter.ViewHolder> {

        private final List<PinnedItem> data;
        private final HashMap<String, RemoteItem> remoteByName;

        PinPickerAdapter(List<PinnedItem> data, HashMap<String, RemoteItem> remoteByName) {
            this.data = new ArrayList<>(data);
            this.remoteByName = remoteByName;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View itemView = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pinned_picker, parent, false);
            return new ViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            PinnedItem item = data.get(position);
            RemoteItem remoteItem = remoteByName.get(item.getRemoteName());
            holder.label.setText(DrawerPinnedUi.resolveDrawerLabel(item, remoteItem));

            if (remoteItem != null) {
                holder.icon.setImageResource(remoteItem.getRemoteIcon());
            } else {
                holder.icon.setImageDrawable(null);
            }

            if (!item.getPath().isEmpty()) {
                holder.subtitle.setText(item.getRemoteName() + ":" + item.getPath());
                holder.subtitle.setVisibility(View.VISIBLE);
            } else {
                holder.subtitle.setVisibility(View.GONE);
            }

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onPinnedItemSelected(item);
                }
                dismiss();
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView label;
            final TextView subtitle;

            ViewHolder(View itemView) {
                super(itemView);
                icon = itemView.findViewById(R.id.pin_icon);
                label = itemView.findViewById(R.id.pin_label);
                subtitle = itemView.findViewById(R.id.pin_subtitle);
            }
        }
    }
}
