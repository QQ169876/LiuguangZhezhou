package com.fongmi.android.tv.cast;

import android.content.Context;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

/**
 * 广播选项（长按播放页那个「广播」按钮，或者从首页菜单点进来）。
 *
 * 界面是用代码堆出来的：这一份两端共用，走代码最省事，也不会因为某一端少个样式就崩。
 *
 * 这里只放**改了就该记住**的东西；「现在是不是正在广播」这种每次重开都该回到默认的状态不进这里。
 */
public final class CastOption {

    private CastOption() {
    }

    public static void show(Context context) {
        int pad = ResUtil.dp2px(24);
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad / 2);

        EditText code = new EditText(context);
        code.setHint(R.string.cast_pair_code);
        code.setSingleLine(true);
        code.setText(CastSetting.getCode());

        EditText max = new EditText(context);
        max.setHint(R.string.cast_max_clients);
        max.setSingleLine(true);
        max.setInputType(InputType.TYPE_CLASS_NUMBER);
        max.setText(CastSetting.maxText()); // 不限制显示成空，跟「留空 = 不限」的说法一致

        CheckBox auto = new CheckBox(context);
        auto.setText(R.string.cast_auto_join);
        auto.setChecked(CastSetting.isAutoJoin());

        int width = ViewGroup.LayoutParams.MATCH_PARENT;
        int wrap = ViewGroup.LayoutParams.WRAP_CONTENT;
        layout.addView(code, width, wrap);
        layout.addView(max, width, wrap);
        layout.addView(auto, width, wrap);

        Spinner transport = choice(context, layout, R.string.cast_transport, R.array.cast_transport_entries, CastSetting.getTransport());
        Spinner screen = choice(context, layout, R.string.cast_screen_mode, R.array.cast_screen_entries, CastSetting.getScreen());
        Spinner quality = choice(context, layout, R.string.cast_quality, R.array.cast_quality_entries, CastSetting.getQuality());

        new AlertDialog.Builder(context)
                .setTitle(R.string.cast_setting)
                .setView(layout)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    CastSetting.putCode(code.getText().toString());
                    CastSetting.putMaxClients(max.getText().toString());
                    CastSetting.putAutoJoin(auto.isChecked());
                    CastSetting.putTransport(transport.getSelectedItemPosition());
                    CastSetting.putScreen(screen.getSelectedItemPosition());
                    CastSetting.putQuality(quality.getSelectedItemPosition());
                    Notify.show(R.string.cast_saved);
                })
                .show();
        App.post(code::requestFocus, 200); // 有输入法的话直接落在第一个框里，少点一下
    }

    /** 一个标题 + 一个下拉：选中的下标就是要存的值，不绕弯 */
    private static Spinner choice(Context context, LinearLayout layout, int labelRes, int entriesRes, int current) {
        TextView label = new TextView(context);
        label.setText(labelRes);
        label.setPadding(ResUtil.dp2px(4), ResUtil.dp2px(12), 0, 0);
        layout.addView(label, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        Spinner spinner = new Spinner(context);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(context, entriesRes, android.R.layout.simple_spinner_dropdown_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(adapter.getCount() - 1, current)));
        layout.addView(spinner, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        return spinner;
    }
}
