package com.fongmi.android.tv.api;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.CatSpider;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.event.CatWebEvent;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

/**
 * 猫源"动作项"的识别。
 *
 * <p>猫源的配置站点（CatPawOpen 的 {@code baseset}）把设置入口伪装成点播条目：
 * {@code vod_id} 是动作名而非片源 id（如 {@code openInternalWebsite}），{@code vod_pic} 是二维码。
 * 点它时 bundle 通过 {@code /msg} 请求宿主打开网页，然后把 {@code detail} 返回成
 * <b>含一个全空条目的列表</b>——列表非空，于是详情页照常打开，用户会看到设置页背后压着空白播放页。
 *
 * <p>判定只看两件事：结果里那个条目<b>什么都没有</b>，且站点是猫源。这两条合起来就是动作项的签名——
 * 真片源至少有名字，真失败会返回空列表（那条路归 {@code setEmpty}）。
 *
 * <p>刻意<b>不</b>依赖"刚刚请求过内嵌网页"这类时序信号：详情结果会被缓存（命中时压根不调 spider，
 * 消息不会再发），且 {@code setDetail} 每次进入会被投递两次（{@code singleTop} 加观察者重投），
 * 时序信号在这两种情况下都会失配。也不做动作名白名单——那些名字属于 bundle，随版本变。
 */
public final class CatAction {

    private CatAction() {
    }

    /**
     * 这次 detail 结果是否只是"打开网页"的副产物，详情页该让位。
     *
     * @param detailStartTime 本次取详情的起始时刻，用来确认开页请求确实由这次导航触发。
     *                        少了这一条，坏掉的 spider 对真片源返回空对象时页面会静默关闭，
     *                        用户只看到闪一下、得不到任何解释。
     */
    public static boolean shouldYieldDetail(String key, long detailStartTime, Result result) {
        if (result == null || result.getList().isEmpty()) return false;
        if (!blank(result.getVod())) return false;
        if (!CatWebEvent.requestedAfter(detailStartTime)) return false;
        return isCatSource(key);
    }

    /**
     * 这个条目有没有任何可显示的东西。
     *
     * <p>宽到足以覆盖 bundle 以后多写几个空字段的情形，窄到不会误判真片源——真片源总有名字，
     * 就算没名字也会有线路、简介或封面。
     */
    public static boolean blank(Vod vod) {
        if (vod == null) return true;
        return vod.getName().isEmpty()
                && vod.getFlags().isEmpty()
                && TextUtils.isEmpty(vod.getContent())
                && TextUtils.isEmpty(vod.getPic());
    }

    /** 站点的 api 是否指向本机 bundle 的爬虫路由。 */
    private static boolean isCatSource(String key) {
        if (TextUtils.isEmpty(key)) return false;
        Site site = VodConfig.get().getSite(key);
        return site != null && CatSpider.matches(site.getApi());
    }
}
