package org.flexstudios.notes.plus;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class UnlockPagerAdapter extends RecyclerView.Adapter<UnlockPagerAdapter.ViewHolder> {

    public interface OnUnlockAttemptListener {
        void onUnlockAttempt(String type, String value);
        void setSwipeEnabled(boolean enabled);
    }

    private final OnUnlockAttemptListener listener;

    public UnlockPagerAdapter(OnUnlockAttemptListener listener) {
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_unlock_page, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.unlockPin.setVisibility(View.GONE);
        holder.unlockPassword.setVisibility(View.GONE);
        holder.unlockPattern.setVisibility(View.GONE);
        holder.btnUnlock.setVisibility(View.VISIBLE);

        if (position == 0) {
            holder.title.setText("Enter PIN");
            holder.unlockPin.setVisibility(View.VISIBLE);
            holder.btnUnlock.setOnClickListener(v -> listener.onUnlockAttempt("PIN", holder.unlockPin.getText().toString()));
        } else if (position == 1) {
            holder.title.setText("Enter Password");
            holder.unlockPassword.setVisibility(View.VISIBLE);
            holder.btnUnlock.setOnClickListener(v -> listener.onUnlockAttempt("PASSWORD", holder.unlockPassword.getText().toString()));
        } else if (position == 2) {
            holder.title.setText("Draw Pattern");
            holder.unlockPattern.setVisibility(View.VISIBLE);
            holder.btnUnlock.setVisibility(View.GONE);
            
            // Fix: Notify activity to disable swiping when interacting with pattern
            holder.unlockPattern.setOnTouchInteractionListener(new PatternView.OnTouchInteractionListener() {
                @Override
                public void onTouchStarted() {
                    listener.setSwipeEnabled(false);
                }

                @Override
                public void onTouchEnded() {
                    listener.setSwipeEnabled(true);
                }
            });

            holder.unlockPattern.setOnPatternListener(pattern -> listener.onUnlockAttempt("PATTERN", pattern));
        }
    }

    @Override
    public int getItemCount() {
        return 3;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView title;
        EditText unlockPin, unlockPassword;
        PatternView unlockPattern;
        Button btnUnlock;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.unlockPageTitle);
            unlockPin = itemView.findViewById(R.id.unlockPin);
            unlockPassword = itemView.findViewById(R.id.unlockPassword);
            unlockPattern = itemView.findViewById(R.id.unlockPattern);
            btnUnlock = itemView.findViewById(R.id.buttonUnlockPage);
        }
    }
}