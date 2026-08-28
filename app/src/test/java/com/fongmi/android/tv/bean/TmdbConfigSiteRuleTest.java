package com.fongmi.android.tv.bean;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * TMDB 站点规则：默认排除项与括号写法归一。
 *
 * <p>默认规则原本只写半角 {@code [音]} 一类，而猫源站点名一律用全角角括号
 * （{@code 「设」配置}、{@code 「盘」木偶}），于是五条默认规则对猫源 57 个站点一条也匹配不上，
 * 连配置站点都会去拉 TMDB。这里锁定归一化行为，以及配置站点被默认排除。
 */
public class TmdbConfigSiteRuleTest {

    private static TmdbConfig fresh() {
        return TmdbConfig.objectFrom("{\"apiKey\":\"k\"}");
    }

    @Test
    public void defaultsCoverNonVideoCategoriesAndSettings() {
        assertEquals("默认排除项：音/听/书/漫/短 加配置站点",
                6, TmdbConfig.getDefaultDisabledRules().size());
        assertTrue("配置站点必须默认排除", TmdbConfig.getDefaultDisabledRules().contains("[设]"));
    }

    @Test
    public void defaultsAreInjectedWhenUserNeverConfigured() {
        assertEquals("用户没配过排除规则时注入默认",
                TmdbConfig.getDefaultDisabledRules(), fresh().getDisabledSites());
    }

    @Test
    public void catSourceSettingSiteIsExcluded() {
        assertFalse("「设」配置 是配置站点，不该跑 TMDB",
                fresh().isSiteEnabled("nodejs_baseset", "「设」配置"));
    }

    @Test
    public void catSourceVideoSitesStayEnabled() {
        TmdbConfig config = fresh();
        assertTrue("「盘」木偶 是片源", config.isSiteEnabled("nodejs_muou", "「盘」木偶"));
        assertTrue("「直」瓜子 是片源", config.isSiteEnabled("nodejs_guazi", "「直」瓜子"));
        assertTrue("「荐」豆瓣 是片源", config.isSiteEnabled("nodejs_douban", "「荐」豆瓣"));
        assertTrue("「采」电影天堂 是片源", config.isSiteEnabled("nodejs_dytt", "「采」电影天堂"));
    }

    @Test
    public void halfWidthRuleMatchesFullWidthSiteName() {
        // 默认规则写的是 [音]，要能命中猫源风格的 「音」
        assertFalse("[音] 应命中 「音」xxx", fresh().isSiteEnabled("nodejs_audio", "「音」听书"));
        assertFalse("[漫] 应命中 【漫】xxx", fresh().isSiteEnabled("nodejs_comic", "【漫】漫画站"));
        assertFalse("[书] 应命中 ［书］xxx", fresh().isSiteEnabled("nodejs_novel", "［书］小说站"));
    }

    @Test
    public void halfWidthSiteNameStillMatches() {
        assertFalse("原本的半角写法不能因归一化而失效",
                fresh().isSiteEnabled("csp_Audio", "[音]喜马拉雅"));
    }

    @Test
    public void userWrittenFullWidthRuleAlsoWorks() {
        TmdbConfig config = TmdbConfig.objectFrom("{\"apiKey\":\"k\",\"exclude\":[\"「盘」\"]}");
        assertFalse("用户手填全角规则要能命中半角站点名", config.isSiteEnabled("x", "[盘]夸克"));
        assertFalse("也要能命中同为全角的站点名", config.isSiteEnabled("y", "「盘」夸克"));
    }

    @Test
    public void unrelatedSiteIsUnaffected() {
        assertTrue("不含任何排除标记的站点照常启用",
                fresh().isSiteEnabled("csp_Bilibili", "哔哩哔哩"));
    }
}
