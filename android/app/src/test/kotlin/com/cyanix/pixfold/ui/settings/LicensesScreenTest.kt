package com.cyanix.pixfold.ui.settings

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.cyanix.pixfold.R
import com.cyanix.pixfold.TestText
import com.cyanix.pixfold.ui.PixFoldNativeTestHost
import com.cyanix.pixfold.ui.PixFoldUiTestDefaults
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.mikepenz.aboutlibraries.entity.License
import com.mikepenz.aboutlibraries.entity.Scm
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 开源许可页交互与「是否跳转外链」确认弹窗全量行为断言（`junit4.v2` + `NATIVE`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = PixFoldUiTestDefaults.QUALIFIER_ZH_PHONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LicensesScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun testLibrary(
        uniqueId: String = "com.example:lib",
        name: String = "ExampleLib",
        website: String? = null,
        scmUrl: String? = null,
        licenseUrl: String? = null,
    ): Library = Library(
        uniqueId = uniqueId,
        artifactVersion = "1.0.0",
        name = name,
        description = null,
        website = website,
        developers = emptyList(),
        organization = null,
        scm = scmUrl?.let { Scm(connection = null, developerConnection = null, url = it) },
        licenses = setOf(
            License(
                name = "Apache-2.0",
                url = licenseUrl,
                year = null,
                spdxId = "Apache-2.0",
                licenseContent = "Apache License\nVersion 2.0, January 2004",
                hash = "hash-$uniqueId",
            ),
        ),
        funding = emptySet(),
        tag = null,
        targets = emptySet(),
    )

    @Test
    fun `tapping own project row asks confirmation and opens repository license url on confirm`() {
        val shadowApp = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
        rule.setContent {
            PixFoldNativeTestHost {
                LicensesScreen(
                    onBack = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
        rule.waitForIdle()
        shadowApp.clearNextStartedActivities()

        rule.onNodeWithTag(TAG_LICENSES_OWN).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_LICENSES_DIALOG).assertIsDisplayed()
        rule.onNodeWithText(URL_OWN_LICENSE, substring = true).assertIsDisplayed()
        assertNull(shadowApp.nextStartedActivity, "点击行后未点「前往」前不应外跳")

        // 先测点「取消」关闭弹窗且不触发外跳
        rule.onNodeWithText(TestText.of(R.string.licenses_open_link_cancel)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LICENSES_DIALOG).assertDoesNotExist()
        assertNull(shadowApp.nextStartedActivity)

        // 再点开并点「前往」确认外跳
        rule.onNodeWithTag(TAG_LICENSES_OWN).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TAG_LICENSES_DIALOG_CONFIRM).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_LICENSES_DIALOG).assertDoesNotExist()
        val launched = shadowApp.nextStartedActivity
        assertNotNull(launched, "点「前往」后应拉起外部浏览器 Intent")
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals(URL_OWN_LICENSE, launched.dataString)
    }

    @Test
    fun `tapping material symbols row asks confirmation and opens icons repository url on confirm`() {
        val shadowApp = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
        rule.setContent {
            PixFoldNativeTestHost {
                LicensesScreen(
                    onBack = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
        rule.waitForIdle()
        shadowApp.clearNextStartedActivities()

        rule.onNodeWithTag(TAG_LICENSES_ICONS).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_LICENSES_DIALOG).assertIsDisplayed()
        rule.onNodeWithText(URL_MATERIAL_ICONS, substring = true).assertIsDisplayed()
        rule.onNodeWithTag(TAG_LICENSES_DIALOG_CONFIRM).performClick()
        rule.waitForIdle()

        val launched = shadowApp.nextStartedActivity
        assertNotNull(launched)
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals(URL_MATERIAL_ICONS, launched.dataString)
    }

    @Test
    fun `tapping third party library asks confirmation without full license text and opens project url on confirm`() {
        val shadowApp = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
        rule.setContent {
            PixFoldNativeTestHost {
                LicensesScreen(
                    onBack = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                )
            }
        }
        rule.waitForIdle()
        shadowApp.clearNextStartedActivities()

        rule.onNodeWithTag(TAG_LICENSES_LIST).performScrollToNode(hasText("Activity"))
        rule.onNodeWithText("Activity").performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_LICENSES_DIALOG).assertIsDisplayed()
        rule.onNodeWithText("https://developer.android.com/jetpack/androidx/releases/activity", substring = true)
            .assertIsDisplayed()
        rule.onNodeWithText("Version 2.0, January 2004", substring = true).assertDoesNotExist()
        assertNull(shadowApp.nextStartedActivity)

        rule.onNodeWithTag(TAG_LICENSES_DIALOG_CONFIRM).performClick()
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_LICENSES_DIALOG).assertDoesNotExist()
        val launched = shadowApp.nextStartedActivity
        assertNotNull(launched)
        assertEquals(Intent.ACTION_VIEW, launched.action)
        assertEquals("https://developer.android.com/jetpack/androidx/releases/activity#1.13.0", launched.dataString)
    }

    @Test
    fun `third party library without valid http url is not clickable and opens no dialog`() {
        val shadowApp = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
        val noUrlLib = testLibrary(
            uniqueId = "com.example:offline-only",
            name = "OfflineOnlyLib",
            website = "   ",
            scmUrl = "scm:git:git://example.com/offline.git",
            licenseUrl = "file:///local/LICENSE",
        )
        rule.setContent {
            PixFoldNativeTestHost {
                LicensesScreen(
                    onBack = {},
                    contentInsets = WindowInsets(0, 0, 0, 0),
                    librariesOverride = Libs(libraries = listOf(noUrlLib), licenses = noUrlLib.licenses),
                )
            }
        }
        rule.waitForIdle()
        shadowApp.clearNextStartedActivities()

        rule.onNodeWithTag(TAG_LICENSES_LIST).performScrollToNode(hasText("OfflineOnlyLib"))
        runCatching { rule.onNodeWithText("OfflineOnlyLib").performClick() }
        rule.waitForIdle()

        rule.onNodeWithTag(TAG_LICENSES_DIALOG).assertDoesNotExist()
        assertNull(shadowApp.nextStartedActivity)
    }

    @Test
    fun `resolveLibraryUrl prefers website falls back to scm and ignores non http schemes`() {
        val prefersWebsite = testLibrary(
            website = " https://example.com/web ",
            scmUrl = "https://github.com/example/scm",
            licenseUrl = "https://opensource.org/licenses/Apache-2.0",
        )
        assertEquals("https://example.com/web", resolveLibraryUrl(prefersWebsite))

        val fallsBackToScm = testLibrary(
            website = "ftp://invalid.example.com",
            scmUrl = "http://github.com/example/scm",
            licenseUrl = "https://opensource.org/licenses/Apache-2.0",
        )
        assertEquals("http://github.com/example/scm", resolveLibraryUrl(fallsBackToScm))

        val fallsBackToLicense = testLibrary(
            website = "",
            scmUrl = "scm:git:https://ignored.example.com/repo.git",
            licenseUrl = "https://opensource.org/licenses/MIT",
        )
        assertEquals("https://opensource.org/licenses/MIT", resolveLibraryUrl(fallsBackToLicense))

        val noValidScheme = testLibrary(
            website = "javascript:alert(1)",
            scmUrl = "scm:git:git://example.com/repo.git",
            licenseUrl = "   ",
        )
        assertNull(resolveLibraryUrl(noValidScheme))
    }
}
