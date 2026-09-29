package com.suyaphot.app.database

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.suyaphot.app.core.media.MetadataReader
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetadataReaderPickerTest {
    @Test fun pickerUriIsNeverRewrittenToRequireOriginal() {
        val raw = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/123")
        assertEquals(raw, MetadataReader(InstrumentationRegistry.getInstrumentation().targetContext).resolve(raw).readUri)
    }
}
