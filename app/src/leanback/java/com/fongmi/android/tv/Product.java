package com.fongmi.android.tv;

import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.ResUtil;

public class Product {

    public static int getDeviceType() {
        return 0;
    }

    public static int getColumn() {
        return Math.abs(PlayerSetting.getSize() - 7);
    }

    public static int getColumn(Style style) {
        return style.isLand() ? getColumn() - 1 : getColumn();
    }

    public static int[] getSpec(Style style) {
        int column = getColumn(style);
        int space = ResUtil.dp2px(48) + ResUtil.dp2px(16 * (column - 1));
        if (style.isOval()) space += ResUtil.dp2px(column * 16);
        return getSpec(space, column, style);
    }

    public static int[] getSpec(int space, int column, Style style) {
        // TV 渲染恒为横屏：取显示长边为宽度，规避竖屏面板设备（如模拟器）窗口度量未随旋转更新的问题
        int screen = Math.max(ResUtil.getScreenWidth(), ResUtil.getScreenHeight());
        int base = screen - space;
        int width = base / column;
        int height = (int) (width / style.getRatio());
        return new int[]{width, height};
    }

    public static int getEms() {
        return Math.min(Math.round((float) ResUtil.getScreenWidth() / ResUtil.sp2px(24)), 35);
    }
}
