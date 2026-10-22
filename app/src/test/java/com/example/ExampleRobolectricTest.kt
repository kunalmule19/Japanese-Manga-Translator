package com.example

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.data.engine.MangaTranslationEngine
import com.example.data.sample.SampleMangaData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Manga Translator", appName)
  }

  @Test
  fun `verify sample manga panels loaded`() {
    val samples = SampleMangaData.samplePages
    assertTrue("Should have at least 3 manga sample pages", samples.size >= 3)
    val battleSample = samples.first()
    assertEquals("Clash of Rivals", battleSample.title)
    assertTrue("Should have translated speech bubbles", battleSample.result.bubbles.isNotEmpty())
    assertNotNull(battleSample.result.bubbles.first().english)
  }

  @Test
  fun `verify perceptual hash and auto diff distance`() {
    val bitmap1 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    bitmap1.eraseColor(android.graphics.Color.WHITE)

    val bitmap2 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    bitmap2.eraseColor(android.graphics.Color.WHITE)

    val hash1 = MangaTranslationEngine.computePerceptualHash(bitmap1)
    val hash2 = MangaTranslationEngine.computePerceptualHash(bitmap2)

    val distanceIdentical = MangaTranslationEngine.hashDistance(hash1, hash2)
    assertEquals("Identical images should have 0 hash distance", 0, distanceIdentical)

    // Altered image (e.g. after page turn)
    val bitmap3 = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
    bitmap3.eraseColor(android.graphics.Color.WHITE)
    for (x in 0 until 50) {
      for (y in 0 until 100) {
        bitmap3.setPixel(x, y, android.graphics.Color.BLACK)
      }
    }
    val hash3 = MangaTranslationEngine.computePerceptualHash(bitmap3)
    val distanceDifferent = MangaTranslationEngine.hashDistance(hash1, hash3)
    assertTrue("Different pages should have significant hash distance (> 0)", distanceDifferent > 0)
  }
}
