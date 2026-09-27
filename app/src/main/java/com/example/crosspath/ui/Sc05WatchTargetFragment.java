package com.example.crosspath.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.crosspath.MainActivity;
import com.example.crosspath.R;
import com.example.crosspath.data.WatchTarget;
import com.example.crosspath.ui.data.UiData;
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
 * ID・名前入力、登録（成功時 Snackbar）、既登録一覧（各行に削除）、タブバー（ホーム・通知）。
 */
public class Sc05WatchTargetFragment extends Fragment {

    private TextInputLayout idInputLayout;
    private TextInputEditText idEditText;
    private TextInputLayout nameInputLayout;
    private TextInputEditText nameEditText;
    private MaterialButton registerButton;
    private RecyclerView watchList;

    /** メモリ上の登録リスト（画面内のみ）。Room の WatchTarget を保持する。 */
    private final List<WatchTarget> registeredList = new ArrayList<>();
    private WatchTargetAdapter adapter;
    /** ローカルの重複判定用。最終判断は DB の戻り値に委ねる。 */
    private final Set<Integer> registeredIds = new HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        boolean emergency = getActivity() instanceof MainActivity
                && ((MainActivity) getActivity()).isEmergencyMode();
        LayoutInflater themedInflater = ScreenThemes.themedLayoutInflater(inflater, Screen.SC05, emergency);
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

        watchList.setLayoutManager(new LinearLayoutManager(requireContext()));
        watchList.setItemAnimator(new DefaultItemAnimator());
        adapter = new WatchTargetAdapter(registeredList);
        watchList.setAdapter(adapter);

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).applyScreenTheme(Screen.SC05);
        }

        registerButton.setOnClickListener(v -> onRegisterClicked());

        // 初期表示: 読み込み完了までは空リスト
        loadWatchTargets();
    }

    /**
     * Room から通知対象一覧を読み込み、リストを更新する。
     */
    private void loadWatchTargets() {
        final View owner = getView();
        UiData.onResult(UiData.execute(repo -> repo.watchTargets()), targets -> {
            if (!isAdded() || getView() != owner) return;
            registeredList.clear();
            registeredIds.clear();
            for (WatchTarget target : targets) {
                registeredList.add(target);
                registeredIds.add(target.targetUserId);
            }
            adapter.notifyDataSetChanged();
        }, error -> {
            if (!isAdded() || getView() != owner) return;
            Snackbar.make(requireView(), "一覧を読み込めません。画面を開き直して再試行してください", Snackbar.LENGTH_LONG).show();
        });
    }

    private void onRegisterClicked() {
        if (!registerButton.isEnabled()) return;
        String idStr = idEditText.getText() != null ? idEditText.getText().toString().trim() : "";
        String name = nameEditText.getText() != null ? nameEditText.getText().toString().trim() : "";

        // 入力検証 — エラーは TextInputLayout#setError で対象欄の下に表示（仕様 §11.12）
        boolean hasError = false;

        // ID の数値パースと範囲チェック
        int idValue = 0;
        if (idStr.isEmpty()) {
            idInputLayout.setError(getString(R.string.sc05_error_invalid_id));
            hasError = true;
        } else {
            try {
                idValue = Integer.parseInt(idStr);
                if (idValue < 1 || idValue > 0xFFFFFF) {
                    idInputLayout.setError(getString(R.string.sc05_error_invalid_id));
                    hasError = true;
                } else if (registeredIds.contains(idValue)) {
                    idInputLayout.setError(getString(R.string.sc05_error_duplicate_id));
                    hasError = true;
                } else {
                    idInputLayout.setError(null);
                }
            } catch (NumberFormatException e) {
                idInputLayout.setError(getString(R.string.sc05_error_invalid_id));
                hasError = true;
            }
        }

        // 名前の空チェック
        if (name.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            nameInputLayout.setError(getString(R.string.sc05_error_name_required));
            hasError = true;
        } else {
            nameInputLayout.setError(null);
        }

        if (hasError) {
            return;
        }

        // 二重送信防止 + DB 登録
        performRegistration(idValue, name);
    }

    /**
     * 検証済みの ID・名前で DB 登録を実行する。
     */
    private void performRegistration(int idValue, String name) {
        final View owner = getView();
        registerButton.setEnabled(false);
        UiData.onResult(UiData.execute(repo -> repo.addWatchTarget(idValue, name)), added -> {
            if (!isAdded() || getView() != owner) return;
            registerButton.setEnabled(true);
            if (added) {
                // 入力欄クリア
                idEditText.setText("");
                nameEditText.setText("");
                idInputLayout.setError(null);
                nameInputLayout.setError(null);
                // Snackbar 表示
                Snackbar.make(requireView(), R.string.sc05_register_success, Snackbar.LENGTH_SHORT).show();
                // 一覧を再読込
                loadWatchTargets();
            } else {
                idInputLayout.setError(getString(R.string.sc05_error_duplicate_id));
            }
        }, error -> {
            if (!isAdded() || getView() != owner) return;
            registerButton.setEnabled(true);
            Snackbar.make(requireView(), R.string.sc05_error_register_failed, Snackbar.LENGTH_SHORT).show();
        });
    }

    /**
     * 削除処理。
     */
    private void onDeleteClicked(WatchTarget target, MaterialButton deleteButton) {
        if (!isAdded() || getView() == null) return;
        final View owner = getView();
        deleteButton.setEnabled(false);
        UiData.onResult(UiData.execute(repo -> repo.deleteWatchTarget(target.targetUserId)), deleted -> {
            if (!isAdded() || getView() != owner) return;
            deleteButton.setEnabled(true);
            loadWatchTargets();
        }, error -> {
            if (!isAdded() || getView() != owner) return;
            deleteButton.setEnabled(true);
            Snackbar.make(requireView(), R.string.sc05_error_delete_failed, Snackbar.LENGTH_SHORT).show();
        });
    }

    /**
     * 既登録者一覧の RecyclerView Adapter。
     */
    private class WatchTargetAdapter extends RecyclerView.Adapter<WatchTargetAdapter.ViewHolder> {

        private final List<WatchTarget> items;

        WatchTargetAdapter(List<WatchTarget> items) {
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
            WatchTarget item = items.get(position);
            holder.idText.setText(String.valueOf(item.targetUserId));
            holder.nameText.setText(item.displayName);
            holder.deleteButton.setEnabled(true);
            holder.deleteButton.setOnClickListener(v ->
                    onDeleteClicked(item, holder.deleteButton));
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