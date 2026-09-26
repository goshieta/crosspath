package com.example.crosspath.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.crosspath.MainActivity;
import com.example.crosspath.R;
import com.example.crosspath.ui.sample.SampleData;
import com.example.crosspath.ui.theme.ScreenThemes;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * SC05 通知対象者管理画面。仕様: 詳細設計書 v0.8 §11.6
 *
 * ID・名前入力、登録（成功時 Snackbar）、既登録一覧（各行に削除）、ホーム（→ SC02）。
 */
public class Sc05WatchTargetFragment extends Fragment {

    private TextInputLayout idInputLayout;
    private TextInputEditText idEditText;
    private TextInputLayout nameInputLayout;
    private TextInputEditText nameEditText;
    private MaterialButton registerButton;
    private RecyclerView watchList;
    private MaterialButton homeButton;

    /** メモリ上の登録リスト（画面内のみ）。TODO(段階2: Room の WatchTarget に置換) */
    private final List<SampleData.SampleWatchTarget> registeredList = new ArrayList<>();
    private WatchTargetAdapter adapter;
    private final Set<String> registeredIds = new HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        LayoutInflater themedInflater = ScreenThemes.themedLayoutInflater(inflater, Screen.SC05);
        return themedInflater.inflate(R.layout.fragment_sc05_watch_target, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        idInputLayout = view.findViewById(R.id.sc05_id_input_layout);
        idEditText = view.findViewById(R.id.sc05_id_edit_text);
        nameInputLayout = view.findViewById(R.id.sc05_name_input_layout);
        nameEditText = view.findViewById(R.id.sc05_name_edit_text);
        registerButton = view.findViewById(R.id.sc05_button_register);
        watchList = view.findViewById(R.id.sc05_watch_list);
        homeButton = view.findViewById(R.id.sc05_button_home);

        // サンプルデータで初期化
        for (SampleData.SampleWatchTarget target : SampleData.WATCH_TARGETS) {
            registeredList.add(target);
            registeredIds.add(target.userId);
        }

        watchList.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new WatchTargetAdapter(registeredList);
        watchList.setAdapter(adapter);

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).applyScreenTheme(Screen.SC05);
        }

        registerButton.setOnClickListener(v -> onRegisterClicked());
        homeButton.setOnClickListener(v -> onHomeClicked());
    }

    private void onRegisterClicked() {
        String id = idEditText.getText() != null ? idEditText.getText().toString().trim() : "";
        String name = nameEditText.getText() != null ? nameEditText.getText().toString().trim() : "";

        // 入力検証 — エラーは TextInputLayout#setError で対象欄の下に表示（仕様 §11.12）
        boolean hasError = false;

        if (id.isEmpty()) {
            idInputLayout.setError(getString(R.string.sc05_error_invalid_id));
            hasError = true;
        } else if (registeredIds.contains(id)) {
            idInputLayout.setError(getString(R.string.sc05_error_duplicate_id));
            hasError = true;
        } else {
            idInputLayout.setError(null);
        }

        if (name.isEmpty()) {
            nameInputLayout.setError(getString(R.string.sc05_error_name_required));
            hasError = true;
        } else {
            nameInputLayout.setError(null);
        }

        if (hasError) {
            return;
        }

        // TODO(段階2: Room の WatchTarget に保存)
        SampleData.SampleWatchTarget newTarget = new SampleData.SampleWatchTarget(id, name, false);
        registeredList.add(newTarget);
        registeredIds.add(id);
        adapter.notifyItemInserted(registeredList.size() - 1);

        // 入力欄クリア
        idEditText.setText("");
        nameEditText.setText("");

        // 成功通知は Snackbar（一時的な結果: 仕様 §11.12 が許容）
        Snackbar.make(requireView(), R.string.sc05_register_success, Snackbar.LENGTH_SHORT).show();
    }

    private void onHomeClicked() {
        // 仕様 §11.1: タイマー作動中は SC04、未開始・終了済みは SC02
        // TODO(段階5: タイマー状態を確認し SC02/SC04 を切り替え)
        getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, new Sc02HomeFragment())
                .addToBackStack(null)
                .commit();
    }

    /**
     * 既登録者一覧の RecyclerView Adapter。
     */
    private class WatchTargetAdapter extends RecyclerView.Adapter<WatchTargetAdapter.ViewHolder> {

        private final List<SampleData.SampleWatchTarget> items;

        WatchTargetAdapter(List<SampleData.SampleWatchTarget> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            View itemView = inflater.inflate(R.layout.item_watch_target, parent, false);
            return new ViewHolder(itemView);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            SampleData.SampleWatchTarget item = items.get(position);
            holder.idText.setText(item.userId);
            holder.nameText.setText(item.displayName);
            holder.deleteButton.setOnClickListener(v -> {
                // TODO(段階2: Room から削除)
                registeredIds.remove(item.userId);
                items.remove(position);
                notifyItemRemoved(position);
                notifyItemRangeChanged(position, items.size());
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final TextView idText;
            final TextView nameText;
            final MaterialButton deleteButton;

            ViewHolder(View itemView) {
                super(itemView);
                idText = itemView.findViewById(R.id.item_watch_id);
                nameText = itemView.findViewById(R.id.item_watch_name);
                deleteButton = itemView.findViewById(R.id.item_watch_delete);
            }
        }
    }
}