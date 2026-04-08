package ca.pkay.rcloneexplorer.Dialogs;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ca.pkay.rcloneexplorer.Items.PinnedItem;
import ca.pkay.rcloneexplorer.Items.PinnedItemStore;
import ca.pkay.rcloneexplorer.R;

public class EditPinnedItemsDialogFragment extends DialogFragment {

    public interface OnPinsEditedListener {
        void onPinsEdited(List<PinnedItem> updatedItems);
    }

    private Context context;
    private OnPinsEditedListener listener;
    private final List<PinnedItem> items = new ArrayList<>();
    private ItemTouchHelper touchHelper;

    public static EditPinnedItemsDialogFragment newInstance() {
        return new EditPinnedItemsDialogFragment();
    }

    public void setOnPinsEditedListener(OnPinsEditedListener listener) {
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
        items.clear();
        items.addAll(PinnedItemStore.load(context));

        LayoutInflater inflater = ((FragmentActivity) context).getLayoutInflater();
        View view = inflater.inflate(R.layout.dialog_edit_pinned_items, null);

        RecyclerView recyclerView = view.findViewById(R.id.recycler_pinned_items);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));

        PinAdapter adapter = new PinAdapter(items);
        recyclerView.setAdapter(adapter);

        DragSwipeCallback callback = new DragSwipeCallback(adapter);
        touchHelper = new ItemTouchHelper(callback);
        touchHelper.attachToRecyclerView(recyclerView);

        adapter.setTouchHelper(touchHelper);

        return new MaterialAlertDialogBuilder(context, R.style.RoundedCornersDialog)
                .setTitle(R.string.edit_pins)
                .setView(view)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    if (listener != null) {
                        listener.onPinsEdited(new ArrayList<>(items));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
    }

    // -------------------------------------------------------------------------
    // Adapter
    // -------------------------------------------------------------------------

    private class PinAdapter extends RecyclerView.Adapter<PinAdapter.ViewHolder> {

        private final List<PinnedItem> data;
        private ItemTouchHelper itemTouchHelper;

        PinAdapter(List<PinnedItem> data) {
            this.data = data;
        }

        void setTouchHelper(ItemTouchHelper helper) {
            this.itemTouchHelper = helper;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_pinned_edit, parent, false);
            return new ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            PinnedItem item = data.get(position);
            holder.label.setText(item.getEffectiveLabel());

            if (!item.getPath().isEmpty()) {
                holder.subtitle.setText(item.getRemoteName() + ":" + item.getPath());
                holder.subtitle.setVisibility(View.VISIBLE);
            } else {
                holder.subtitle.setVisibility(View.GONE);
            }

            holder.dragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && itemTouchHelper != null) {
                    itemTouchHelper.startDrag(holder);
                }
                return false;
            });

            holder.deleteBtn.setOnClickListener(v -> {
                int pos = holder.getAdapterPosition();
                if (pos != RecyclerView.NO_ID) {
                    data.remove(pos);
                    notifyItemRemoved(pos);
                }
            });
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        void onItemMoved(int from, int to) {
            if (from < to) {
                for (int i = from; i < to; i++) {
                    Collections.swap(data, i, i + 1);
                }
            } else {
                for (int i = from; i > to; i--) {
                    Collections.swap(data, i, i - 1);
                }
            }
            notifyItemMoved(from, to);
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final ImageView dragHandle;
            final TextView label;
            final TextView subtitle;
            final ImageButton deleteBtn;

            ViewHolder(View itemView) {
                super(itemView);
                dragHandle = itemView.findViewById(R.id.drag_handle);
                label = itemView.findViewById(R.id.pin_label);
                subtitle = itemView.findViewById(R.id.pin_subtitle);
                deleteBtn = itemView.findViewById(R.id.btn_delete_pin);
            }
        }
    }

    // -------------------------------------------------------------------------
    // ItemTouchHelper callback
    // -------------------------------------------------------------------------

    private static class DragSwipeCallback extends ItemTouchHelper.SimpleCallback {

        private final PinAdapter adapter;

        DragSwipeCallback(PinAdapter adapter) {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.START | ItemTouchHelper.END);
            this.adapter = adapter;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            adapter.onItemMoved(viewHolder.getAdapterPosition(), target.getAdapterPosition());
            return true;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            int position = viewHolder.getAdapterPosition();
            if (position != RecyclerView.NO_ID) {
                adapter.data.remove(position);
                adapter.notifyItemRemoved(position);
            }
        }
    }
}
