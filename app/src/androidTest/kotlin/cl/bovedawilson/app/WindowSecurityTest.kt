package cl.bovedawilson.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WindowSecurityTest {
    @Test(timeout = 30_000)
    fun mainWindowKeepsFlagSecureAcrossRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val original = AtomicReference<MainActivity?>()
        val recreated = AtomicReference<MainActivity?>()
        val created = CountDownLatch(1)
        val resumed = CountDownLatch(1)
        val callbacks = recreationCallbacks(original, recreated, created, resumed)
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            val launchOutput = ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand(
                    "am start -W -n ${instrumentation.targetContext.packageName}/.MainActivity",
                ),
            ).bufferedReader().use { it.readText() }
            assertTrue("MainActivity launch failed: $launchOutput", launchOutput.contains("Status: ok"))
            assertTrue("MainActivity was not created", created.await(15, TimeUnit.SECONDS))
            val first = requireNotNull(original.get())
            instrumentation.runOnMainSync { assertFlagSecure(first) }
            instrumentation.runOnMainSync { first.recreate() }
            assertTrue("Recreated activity did not resume", resumed.await(15, TimeUnit.SECONDS))
            instrumentation.runOnMainSync { assertFlagSecure(requireNotNull(recreated.get())) }
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
            val activity = recreated.get() ?: original.get()
            activity?.let { current -> instrumentation.runOnMainSync { current.finish() } }
        }
    }

    private fun assertFlagSecure(activity: MainActivity) {
        val flags = activity.window.attributes.flags
        assertTrue(flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }

    private fun recreationCallbacks(
        original: AtomicReference<MainActivity?>,
        recreated: AtomicReference<MainActivity?>,
        created: CountDownLatch,
        resumed: CountDownLatch,
    ) = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, state: Bundle?) {
            if (activity is MainActivity) {
                if (original.compareAndSet(null, activity)) {
                    created.countDown()
                } else if (activity !== original.get()) {
                    recreated.set(activity)
                }
            }
        }

        override fun onActivityResumed(activity: Activity) {
            if (activity is MainActivity && activity !== original.get()) {
                recreated.set(activity)
                resumed.countDown()
            }
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
