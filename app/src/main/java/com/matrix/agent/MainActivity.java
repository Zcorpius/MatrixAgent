package com.matrix.agent;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.matrix.agent.presentation.ui.AgentTestFragment;
import com.matrix.agent.presentation.ui.ModelApiFragment;
import com.matrix.agent.presentation.ui.ModelDownloadFragment;
import com.matrix.agent.presentation.viewmodel.VoiceEntryProvider;
import com.matrix.agent.presentation.viewmodel.VoiceEntryProviders;

/** Application shell. Business logic lives in repositories and ViewModels. */
public final class MainActivity extends AppCompatActivity {
    private DrawerLayout drawerLayout;
    private TextView pageTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        drawerLayout = findViewById(R.id.drawer_layout);
        pageTitle = findViewById(R.id.page_title);
        findViewById(R.id.menu_button).setOnClickListener(
                view -> drawerLayout.openDrawer(GravityCompat.START));
        findViewById(R.id.nav_agent).setOnClickListener(
                view -> showPage(new AgentTestFragment(), "Agent 测试"));
        findViewById(R.id.nav_model).setOnClickListener(
                view -> showPage(new ModelApiFragment(), "模型 API 接入"));
        findViewById(R.id.nav_model_download).setOnClickListener(
                view -> showPage(new ModelDownloadFragment(), "模型下载"));
        // Voice 入口经 VoiceEntryProvider 解耦:仅 debug 构建有实现(createVoiceFragment),
        // release 时 provider=null 隐藏入口。main 不直接引用 voice Demo 类(Release 不含 Vosk)。
        View navVoice = findViewById(R.id.nav_voice);
        VoiceEntryProvider voiceProvider = VoiceEntryProviders.get();
        navVoice.setVisibility(voiceProvider != null ? View.VISIBLE : View.GONE);
        if (voiceProvider != null) {
            navVoice.setOnClickListener(
                    view -> showPage(voiceProvider.createVoiceFragment(), "语音闭环"));
        }
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        if (savedInstanceState == null) {
            showPage(new AgentTestFragment(), "Agent 测试");
        }
    }

    /** 切换主内容 Fragment。public：ModelDownloadFragment 的"选用"按钮跨页跳转时调用。 */
    public void showPage(Fragment fragment, String title) {
        pageTitle.setText(title);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit();
        drawerLayout.closeDrawer(GravityCompat.START);
    }

}
