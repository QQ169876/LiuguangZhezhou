package com.fongmi.android.tv.ui.activity;
import java.util.Collections;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.TmdbWork;
import com.fongmi.android.tv.bean.Word;
import com.fongmi.android.tv.databinding.ActivitySearchBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.AiSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.AiPickAdapter;
import com.fongmi.android.tv.ui.adapter.RecordAdapter;
import com.fongmi.android.tv.ui.adapter.WordAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomKeyboard;
import com.fongmi.android.tv.ui.custom.CustomTextListener;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.utils.AiSearchRunner;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Util;
import com.fongmi.android.tv.utils.ZhuToPin;
import com.github.catvod.net.OkHttp;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayoutManager;
import com.google.common.net.HttpHeaders;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import okhttp3.Call;
import okhttp3.Response;

public class SearchActivity extends BaseActivity implements WordAdapter.OnClickListener, RecordAdapter.OnClickListener, AiPickAdapter.OnClickListener, CustomKeyboard.Callback {

    private ActivitySearchBinding mBinding;
    private RecordAdapter mRecordAdapter;
    private WordAdapter mWordAdapter;
    private AiPickAdapter mAiAdapter;
    private boolean aiMode;    // 右侧结果区正被智能搜索占着
    private boolean aiVoice;   // 这次语音是「AI」键起的，说完直接进智能搜索
    private String aiQuestion = "";

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SearchActivity.class));
    }

    public static void start(Activity activity, String keyword) {
        Intent intent = new Intent(activity, SearchActivity.class);
        intent.putExtra("keyword", keyword);
        activity.startActivity(intent);
    }

    private String getKeyword() {
        String keyword = getIntent().getStringExtra("keyword");
        return keyword != null ? keyword : "";
    }

    private boolean empty() {
        return mBinding.keyword.getText().toString().trim().isEmpty();
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySearchBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        CustomKeyboard.init(this, mBinding);
        setRecyclerView();
        checkKeyword();
        onSearch();
    }

    @Override
    protected void initEvent() {
        mBinding.keyword.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onSearch();
            return true;
        });
        mBinding.keyword.addTextChangedListener(new CustomTextListener() {
            @Override
            public void afterTextChanged(Editable s) {
                getWord(s.toString());
            }
        });
        mBinding.mic.setOnClickListener(v -> {
            aiVoice = false; // 直接点麦克风是普通语音输入，别跟「AI」键起的语音混了
            mBinding.mic.start();
        });
        mBinding.mic.setListener(this, new CustomTextListener() {
            @Override
            public void onResults(String result) {
                if (!result.isEmpty()) setKeyword(result);
                // 从「AI」键起的语音：说完直接进智能搜索，不用再多按一下
                if (aiVoice && !result.isEmpty()) {
                    aiVoice = false;
                    onAi();
                    return;
                }
                aiVoice = false;
                mBinding.keyword.requestFocus();
            }
        });
    }

    private void setRecyclerView() {
        mBinding.wordRecycler.setItemAnimator(null);
        mBinding.wordRecycler.setHasFixedSize(false);
        mBinding.wordRecycler.setLayoutManager(new FlexboxLayoutManager(this, FlexDirection.ROW));
        mBinding.wordRecycler.setAdapter(mWordAdapter = new WordAdapter(this));
        mBinding.recordRecycler.setHasFixedSize(false);
        mBinding.recordRecycler.setLayoutManager(new FlexboxLayoutManager(this, FlexDirection.ROW));
        mBinding.recordRecycler.setAdapter(mRecordAdapter = new RecordAdapter(this));
        mBinding.aiRecycler.setItemAnimator(null);
        mBinding.aiRecycler.setHasFixedSize(false);
        mBinding.aiRecycler.setAdapter(mAiAdapter = new AiPickAdapter(this));
    }

    private void checkKeyword() {
        setKeyword(getKeyword());
        getWord(getKeyword());
    }

    private void setKeyword(String text) {
        mBinding.keyword.setText(text);
        mBinding.keyword.setSelection(text.length());
    }

    private void getWord(String text) {
        exitAiMode();
        if (text.isEmpty()) getHot();
        else getSuggest(text);
    }

    private void getHot() {
        mBinding.word.setText(R.string.search_hot);
        mWordAdapter.setItems(Word.objectFrom(Setting.getHot()).getData());
        OkHttp.newCall("https://api.web.360kan.com/v1/rank?cat=1", Collections.singletonMap(HttpHeaders.REFERER, "https://www.360kan.com/rank/general")).enqueue(getCallback(true));
    }

    private void getSuggest(String text) {
        mBinding.word.setText(R.string.search_suggest);
        OkHttp.newCall("https://suggest.video.iqiyi.com/?if=mobile&key=" + Uri.encode(ZhuToPin.get(text))).enqueue(getCallback(false));
    }

    private Callback getCallback(boolean hot) {
        return new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String result = response.body().string();
                if (TextUtils.isEmpty(result)) return;
                App.post(() -> setAdapter(result, hot));
            }
        };
    }

    private void setAdapter(String result, boolean save) {
        if (!save && empty()) return;
        if (save) Setting.putHot(result);
        mWordAdapter.setItems(Word.objectFrom(result).getData());
    }

    @Override
    public void onItemClick(String text) {
        setKeyword(text);
        onSearch();
    }

    @Override
    public void onDataChanged(int size) {
        if (aiMode) return; // 智能搜索结果占着结果区，别被历史记录的增删搅了
        mBinding.recordLayout.setVisibility(size == 0 ? View.GONE : View.VISIBLE);
        if (size == 0) focusFirst(mBinding.wordRecycler);
    }

    @Override
    public void onSearch() {
        if (empty()) return;
        String keyword = mBinding.keyword.getText().toString().trim();
        App.post(() -> mRecordAdapter.add(keyword), 250);
        Util.hideKeyboard(mBinding.keyword);
        CollectActivity.start(this, keyword);
    }

    @Override
    public void showDialog() {
        SiteDialog.create().search().show(this);
    }

    /**
     * 键盘末尾的「AI」键：把搜索框里那句大白话交给大模型理解成找片指令，结果直接摆在右侧结果区。
     * 两条路都通：框里有字 → 直接按 AI 搜（用键盘敲完字按 AI，跟按「搜索」键是平级的另一种搜法）；
     * 框里没字 → 有语音能力的设备先起语音，说完自动进智能搜索；没有语音能力的（部分电视/盒子）
     * 就提醒先打字，焦点送回搜索框。
     */
    @Override
    public void onAi() {
        if (!AiSetting.isEnabled()) {
            Notify.show(R.string.ai_search_off);
            return;
        }
        if (empty()) {
            if (mBinding.mic.voiceAvailable()) {
                aiVoice = true; // 标记一下：这次语音识别的结果要进智能搜索，不是普通搜索
                mBinding.mic.start();
            } else {
                // 这台设备没有语音输入：别干等，把焦点放回框里让他用键盘敲
                Notify.show(R.string.ai_search_ask_first);
                mBinding.keyword.requestFocus();
            }
            return;
        }
        Util.hideKeyboard(mBinding.keyword);
        aiQuestion = mBinding.keyword.getText().toString().trim();
        aiMode = true;
        mBinding.word.setVisibility(View.GONE);
        mBinding.wordRecycler.setVisibility(View.GONE);
        mBinding.recordLayout.setVisibility(View.GONE);
        mBinding.aiRecycler.setVisibility(View.GONE);
        mBinding.aiSummary.setVisibility(View.VISIBLE);
        AiSearchRunner.run(aiQuestion, aiView);
    }

    /** 智能搜索的界面回调：结果区就在本页右侧，问句变了（用户又打字了）就把迟到的结果扔掉 */
    private final AiSearchRunner.View aiView = new AiSearchRunner.View() {

        private boolean expired() {
            return !aiMode || !mBinding.keyword.getText().toString().trim().equals(aiQuestion);
        }

        @Override
        public void onThinking() {
            mBinding.aiSummary.setText(R.string.ai_search_thinking);
        }

        @Override
        public void onSummary(String summary) {
            if (expired() || summary.isEmpty()) return;
            mBinding.aiSummary.setText(getString(R.string.ai_search_understand, summary));
        }

        @Override
        public void onRows(List<TmdbWork> rows) {
            if (expired()) return;
            mAiAdapter.setItems(rows);
            mBinding.aiRecycler.setVisibility(View.VISIBLE);
            focusFirst(mBinding.aiRecycler);
        }

        @Override
        public void onEnrich(int position, TmdbWork work) {
            if (expired()) return;
            mAiAdapter.update(position, work);
        }

        @Override
        public void onTip(int resId) {
            if (expired()) return;
            mBinding.aiRecycler.setVisibility(View.GONE);
            mBinding.aiSummary.setText(resId);
        }

        @Override
        public void onDirect(String keyword) {
            if (expired()) return;
            setKeyword(keyword);
            onSearch();
        }
    };

    /** 用户又打字了：退出智能搜索，结果区还给热搜/联想词 */
    private void exitAiMode() {
        if (!aiMode) return;
        aiMode = false;
        mBinding.aiSummary.setVisibility(View.GONE);
        mBinding.aiRecycler.setVisibility(View.GONE);
        mBinding.word.setVisibility(View.VISIBLE);
        mBinding.wordRecycler.setVisibility(View.VISIBLE);
        mBinding.recordLayout.setVisibility(mRecordAdapter.getItemCount() == 0 ? View.GONE : View.VISIBLE);
    }

    /** 点了智能搜索给出的某一候选：拿准片名走正常的全站搜索 */
    @Override
    public void onItemClick(TmdbWork item) {
        setKeyword(item.getName());
        onSearch();
    }

    @Override
    public void onRemote() {
        PushActivity.start(this, 1);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) showDialog();
        if (KeyUtil.isActionDown(event) && findFocus(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    private boolean findFocus(KeyEvent event) {
        View current = getCurrentFocus();
        if (current == mBinding.keyword) return handleKeywordKey(event);
        View inKeyboard = mBinding.keyboard.findContainingItemView(current);
        View inWord = mBinding.wordRecycler.findContainingItemView(current);
        View inRecord = mBinding.recordRecycler.findContainingItemView(current);
        if (inKeyboard != null) return handleKeyboardKey(event, inKeyboard);
        if (inRecord != null) return handleRecordKey(event, inRecord);
        if (inWord != null) return handleWordKey(event, inWord);
        return false;
    }

    private View findNearestInLastRow(RecyclerView rv, int targetLeft) {
        if (rv.getChildCount() == 0) return null;
        int lastTop = rv.getChildAt(rv.getChildCount() - 1).getTop();
        View nearest = null;
        int minDist = Integer.MAX_VALUE;
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child.getTop() == lastTop) {
                int dist = Math.abs(child.getLeft() - targetLeft);
                if (dist < minDist) {
                    minDist = dist;
                    nearest = child;
                }
            }
        }
        return nearest;
    }

    private boolean isFirstRow(RecyclerView rv, View item) {
        View first = rv.getChildAt(0);
        return first != null && item.getTop() == first.getTop();
    }

    private boolean isLastRow(RecyclerView rv, View item) {
        View last = rv.getChildAt(rv.getChildCount() - 1);
        return last != null && item.getTop() == last.getTop();
    }

    private boolean isFirstInRow(RecyclerView rv, View focused) {
        int top = focused.getTop();
        int left = focused.getLeft();
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child.getTop() == top && child.getLeft() < left) return false;
        }
        return true;
    }

    private boolean isLastInRow(RecyclerView rv, View focused) {
        int top = focused.getTop();
        int right = focused.getRight();
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            if (child.getTop() == top && child.getRight() > right) return false;
        }
        return true;
    }

    private boolean handleKeywordKey(KeyEvent event) {
        if (!KeyUtil.isRightKey(event)) return false;
        if (mBinding.keyword.getSelectionEnd() < mBinding.keyword.getText().length()) return false;
        boolean hasRecord = mBinding.recordLayout.getVisibility() == View.VISIBLE;
        return focusFirst(hasRecord ? mBinding.recordRecycler : mBinding.wordRecycler);
    }

    private boolean handleKeyboardKey(KeyEvent event, View item) {
        if (KeyUtil.isUpKey(event) && isFirstRow(mBinding.keyboard, item)) {
            mBinding.keyword.requestFocus();
            return true;
        }
        if (KeyUtil.isLeftKey(event) && isFirstInRow(mBinding.keyboard, item)) return true;
        return KeyUtil.isDownKey(event) && isLastRow(mBinding.keyboard, item);
    }

    private boolean handleWordKey(KeyEvent event, View item) {
        if (KeyUtil.isRightKey(event)) return isLastInRow(mBinding.wordRecycler, item);
        if (KeyUtil.isDownKey(event)) return isLastRow(mBinding.wordRecycler, item);
        if (KeyUtil.isUpKey(event) && isFirstRow(mBinding.wordRecycler, item)) {
            if (mBinding.recordLayout.getVisibility() == View.VISIBLE) {
                View child = findNearestInLastRow(mBinding.recordRecycler, item.getLeft());
                if (child != null) {
                    mBinding.scroll.smoothScrollTo(0, 0);
                    child.requestFocus();
                    return true;
                }
            }
            return true;
        }
        return false;
    }

    private boolean handleRecordKey(KeyEvent event, View item) {
        if (KeyUtil.isRightKey(event)) return isLastInRow(mBinding.recordRecycler, item);
        if (KeyUtil.isUpKey(event)) return isFirstRow(mBinding.recordRecycler, item);
        if (KeyUtil.isDownKey(event) && isLastRow(mBinding.recordRecycler, item)) return focusFirst(mBinding.wordRecycler);
        return false;
    }

    private boolean focusFirst(RecyclerView rv) {
        View child = rv.getChildAt(0);
        if (child == null) return false;
        child.requestFocus();
        return true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mBinding.mic.setFocusable(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mBinding.mic.setFocusable(true);
        mBinding.keyword.requestFocus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mBinding.mic.destroy();
    }
}
