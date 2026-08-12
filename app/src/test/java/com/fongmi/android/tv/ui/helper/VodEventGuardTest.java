package com.fongmi.android.tv.ui.helper;

import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VodEventGuardTest {

    @Test
    public void matchesSameSiteAndId() {
        assertTrue(VodEventGuard.matches(vod("site-a", "123"), "site-a", "123"));
    }

    @Test
    public void matchesIntentPageSuffix() {
        assertTrue(VodEventGuard.matches(vod("site-a", "123"), "site-a", "123/40"));
        assertEquals("123", VodEventGuard.stripPageSuffix("123/40"));
    }

    @Test
    public void preservesLeadingSlashIds() {
        assertEquals("/index.php/id/1", VodEventGuard.stripPageSuffix("/index.php/id/1"));
    }

    @Test
    public void rejectsDifferentSiteOrId() {
        assertFalse(VodEventGuard.matches(vod("site-a", "123"), "site-b", "123"));
        assertFalse(VodEventGuard.matches(vod("site-a", "123"), "site-a", "124"));
    }

    @Test
    public void allowsMissingSourceIdentityFields() {
        assertTrue(VodEventGuard.matches(vod("", ""), "site-a", "123"));
    }

    @Test
    public void matchesFileUriWhenEventAddsLeadingSlash() {
        String id = "file:///data/user/0/app/files/video.mp4";
        assertTrue(VodEventGuard.matches(vod("", "/" + id), "push_agent", id));
        assertEquals(id, VodEventGuard.stripPageSuffix(id));
        assertEquals(id, VodEventGuard.stripPageSuffix("/" + id));
    }

    @Test
    public void preservesNetworkUriPaths() {
        String id = "https://example.com/video/1";
        assertEquals(id, VodEventGuard.stripPageSuffix(id));
        assertFalse(VodEventGuard.matches(vod("site-a", "https://example.com/video/2"), "site-a", id));
    }

    private static Vod vod(String siteKey, String id) {
        Vod vod = new Vod();
        vod.setSite(Site.get(siteKey, siteKey));
        vod.setId(id);
        return vod;
    }
}
