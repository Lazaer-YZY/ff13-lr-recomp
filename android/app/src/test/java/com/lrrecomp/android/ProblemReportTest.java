package com.lrrecomp.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.net.Uri;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** ProblemReport's issue form link and its fields (CTRL -> Troubleshooting -> Report a problem on GitHub). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ProblemReportTest {
    @Test
    public void deviceLineNamesTheMakerOnce() {
        assertEquals("AYN Thor, SM8550, Android 13", ProblemReport.deviceLine("AYN", "AYN Thor", "SM8550", "13"));
        assertEquals("samsung SM-G973F, exynos9820, Android 12",
            ProblemReport.deviceLine("samsung", "SM-G973F", "exynos9820", "12"));
        assertEquals("Xiaomi 2210132G, Android 14", ProblemReport.deviceLine("Xiaomi", "2210132G", "", "14"));
    }

    @Test
    public void settingsAreSortedSkipBookkeepingAndStayShort() {
        Map<String, Object> prefs = new LinkedHashMap<>();
        prefs.put("wide", true);
        prefs.put("fps", "30");
        prefs.put("debug_log_default_on", true);
        prefs.put("fps40_trial", false);
        assertEquals("head\nfps=30\nwide=true", ProblemReport.settingsText("head", prefs, 1500));
        for (int i = 0; i < 200; ++i) prefs.put("key" + i, "value" + i);
        String cut = ProblemReport.settingsText("head", prefs, 300);
        assertTrue(cut, cut.length() <= 300 + 4);
        assertTrue(cut, cut.endsWith("\n..."));
    }

    @Test
    public void issueLinkOpensTheReportFormWithItsFieldsFilledIn() {
        Uri uri = ProblemReport.buildIssueUri(ProblemReport.REPOSITORY, "1.2.6", "AYN Thor, SM8550, Android 13",
            "Adreno (TM) 740, System", "Debug logging this run: on\nfps=30 & more", "Attach x.zip here:\n\n");
        assertTrue(uri.toString(), uri.toString().startsWith(
            "https://github.com/Lazaer-YZY/ff13-lr-recomp/issues/new?template=report.yml&"));
        assertEquals("1.2.6", uri.getQueryParameter("version"));
        assertEquals("AYN Thor, SM8550, Android 13", uri.getQueryParameter("device"));
        assertEquals("Adreno (TM) 740, System", uri.getQueryParameter("gpu"));
        // A newline and an ampersand survive the trip (the settings field is a text area).
        assertEquals("Debug logging this run: on\nfps=30 & more", uri.getQueryParameter("settings"));
        assertEquals("Attach x.zip here:\n\n", uri.getQueryParameter("logs"));
    }
}
