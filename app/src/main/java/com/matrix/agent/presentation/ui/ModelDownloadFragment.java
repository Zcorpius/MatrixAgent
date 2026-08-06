package com.matrix.agent.presentation.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.matrix.agent.MainActivity;
import com.matrix.agent.R;
import com.matrix.agent.app.AppContainer;
import com.matrix.agent.app.MatrixAgentApplication;
import com.matrix.agent.data.db.ModelDownloadEntity;
import com.matrix.agent.data.download.ModelDownloadManager;
import com.matrix.agent.data.download.ModelMarketClient;
import com.matrix.agent.presentation.viewmodel.MatrixViewModelFactory;
import com.matrix.agent.presentation.viewmodel.ModelDownloadViewModel;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 端侧模型下载页面——模型市场列表 + 已下载模型列表 + 下载进度状态。
 *
 * <p>不引入 RecyclerView：用 LinearLayout 动态 addView 渲染每行（TextView + Button）。
 * ViewModel 绑 Activity scope（与 ModelApiFragment 一致，replace 时不丢状态）。
 *
 * <p>进度刷新：onResume 起一个 Handler 周期调 {@link ModelDownloadViewModel#refreshDownloads()}
 * 拉最新 DAO 状态（DOWNLOADING 期间百分比实时更新），onPause 停。
 */
public final class ModelDownloadFragment extends Fragment {
    private static final long REFRESH_INTERVAL_MS = 1200L;

    private ModelDownloadViewModel viewModel;
    @Nullable private ModelDownloadManager manager;
    private LinearLayout marketContainer;
    private LinearLayout downloadedContainer;
    private TextView noticeText;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            viewModel.refreshDownloads();
            handler.postDelayed(this, REFRESH_INTERVAL_MS);
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_model_download, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        AppContainer container = ((MatrixAgentApplication) requireActivity().getApplication()).getContainer();
        manager = container.getModelDownloadManager();
        // Activity scope：与 ModelApiFragment 一致（MainActivity.showPage 用 replace，绑 Fragment 会丢状态）。
        viewModel = new ViewModelProvider(requireActivity(), new MatrixViewModelFactory(container))
                .get(ModelDownloadViewModel.class);

        marketContainer = view.findViewById(R.id.market_container);
        downloadedContainer = view.findViewById(R.id.downloaded_container);
        noticeText = view.findViewById(R.id.download_notice);

        view.findViewById(R.id.refresh_market_button).setOnClickListener(v -> {
            viewModel.refreshMarket();
            viewModel.refreshDownloads();
        });

        viewModel.getMarketModels().observe(getViewLifecycleOwner(), models -> render());
        viewModel.getDownloads().observe(getViewLifecycleOwner(), dl -> render());
        viewModel.getNotice().observe(getViewLifecycleOwner(), msg -> {
            if (!TextUtils.isEmpty(msg)) noticeText.setText(msg);
        });

        viewModel.refreshMarket();
        viewModel.refreshDownloads();
    }

    @Override
    public void onResume() {
        super.onResume();
        viewModel.refreshDownloads();
        handler.post(refreshTick);
    }

    @Override
    public void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshTick);
    }

    /** 任意数据源（marketModels / downloads）变化时全量重渲染两个容器。 */
    private void render() {
        renderMarket();
        renderDownloaded();
    }

    private void renderMarket() {
        marketContainer.removeAllViews();
        List<ModelMarketClient.ModelEntry> models = viewModel.getMarketModels().getValue();
        if (models == null || models.isEmpty()) {
            marketContainer.addView(hintView("（尚未加载模型市场，点击「刷新模型市场」）"));
            return;
        }
        Map<String, ModelDownloadEntity> byName = downloadsByName();
        for (ModelMarketClient.ModelEntry entry : models) {
            marketContainer.addView(buildMarketRow(entry, byName.get(entry.modelName)));
        }
    }

    private View buildMarketRow(ModelMarketClient.ModelEntry entry, @Nullable ModelDownloadEntity entity) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        TextView info = new TextView(requireContext());
        info.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String sizeText = entry.sizeGb > 0 ? String.format("%.2f GB", entry.sizeGb) : "未知大小";
        StringBuilder text = new StringBuilder()
                .append(entry.modelName).append("  ·  ").append(sizeText);
        if (entry.modelScopeRepo == null || entry.modelScopeRepo.isEmpty()) {
            text.append("\n（无 ModelScope 源）");
        }
        int pct = 0;
        if (entity != null) {
            pct = computePct(entity);
            text.append("\n状态：").append(statusLabel(entity.status, pct));
        }
        info.setText(text);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        info.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_text));

        Button btn = new Button(requireContext());
        boolean downloading = entity != null
                && ModelDownloadManager.STATUS_DOWNLOADING.equals(entity.status);
        boolean completed = manager != null && manager.isDownloaded(entry.modelName);
        if (completed) {
            btn.setText("已下载");
            btn.setEnabled(false);
        } else if (downloading) {
            btn.setText("取消 " + pct + "%");
            btn.setOnClickListener(v -> {
                viewModel.cancelDownload(entry.modelName);
                Toast.makeText(requireContext(), "已取消 " + entry.modelName, Toast.LENGTH_SHORT).show();
            });
        } else if (entity != null && ModelDownloadManager.STATUS_PAUSED.equals(entity.status)) {
            // 需求1：PAUSED（kill 后 .tmp 残留、未完成）显示"继续"，点击重新下载，downloadFileWithResume 基线断点续传。
            btn.setText("继续 " + pct + "%");
            btn.setOnClickListener(v -> viewModel.startDownload(entry));
        } else if (entity != null && ModelDownloadManager.STATUS_FAILED.equals(entity.status)) {
            btn.setText("重试");
            btn.setOnClickListener(v -> viewModel.startDownload(entry));
        } else {
            boolean hasRepo = entry.modelScopeRepo != null && !entry.modelScopeRepo.isEmpty();
            btn.setText("下载");
            btn.setEnabled(hasRepo);
            btn.setOnClickListener(v -> viewModel.startDownload(entry));
        }
        row.addView(info);
        row.addView(btn);
        return row;
    }

    private void renderDownloaded() {
        downloadedContainer.removeAllViews();
        List<String> local = manager != null ? manager.listLocalModels() : Collections.<String>emptyList();
        if (local.isEmpty()) {
            downloadedContainer.addView(hintView("（暂无已下载模型）"));
            return;
        }
        for (String name : local) {
            downloadedContainer.addView(buildDownloadedRow(name));
        }
    }

    private View buildDownloadedRow(String modelName) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        row.setLayoutParams(lp);

        TextView name = new TextView(requireContext());
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        name.setText(modelName);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_text));

        Button use = new Button(requireContext());
        use.setText("选用");
        use.setOnClickListener(v -> {
            // 需求3：复制模型名到剪贴板，方便粘贴到 ModelApiFragment 的 model 字段。
            ClipboardManager clipboard = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("model_name", modelName);
                clipboard.setPrimaryClip(clip);
            }
            Toast.makeText(requireContext(), "已复制: " + modelName, Toast.LENGTH_SHORT).show();
            // 跳到 ModelApiFragment 并设端侧 + model=目录名（通过 Fragment arguments 传递）。
            ModelApiFragment fragment = new ModelApiFragment();
            Bundle args = new Bundle();
            args.putString(ModelApiFragment.ARG_ON_DEVICE_MODEL, modelName);
            fragment.setArguments(args);
            ((MainActivity) requireActivity()).showPage(fragment, "模型 API 接入");
        });

        // 需求2：删除已下载模型（递归删目录 + DAO 记录）。
        Button delete = new Button(requireContext());
        delete.setText("删除");
        delete.setOnClickListener(v -> viewModel.deleteModel(modelName));

        row.addView(name);
        row.addView(use);
        row.addView(delete);
        return row;
    }

    private View hintView(String text) {
        TextView tv = new TextView(requireContext());
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tv.setTextColor(ContextCompat.getColor(requireContext(), R.color.matrix_muted));
        return tv;
    }

    private Map<String, ModelDownloadEntity> downloadsByName() {
        Map<String, ModelDownloadEntity> map = new HashMap<>();
        List<ModelDownloadEntity> list = viewModel.getDownloads().getValue();
        if (list != null) {
            for (ModelDownloadEntity e : list) map.put(e.modelName, e);
        }
        return map;
    }

    private static int computePct(ModelDownloadEntity e) {
        if (e == null || e.totalBytes <= 0) return 0;
        long d = Math.max(0, e.downloadedBytes);
        long pct = d * 100 / e.totalBytes;
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        return (int) pct;
    }

    private static String statusLabel(String status, int pct) {
        switch (status) {
            case ModelDownloadManager.STATUS_DOWNLOADING: return "下载中 " + pct + "%";
            case ModelDownloadManager.STATUS_COMPLETED: return "已完成";
            case ModelDownloadManager.STATUS_FAILED: return "失败";
            case ModelDownloadManager.STATUS_PAUSED: return "已暂停";
            default: return "空闲";
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
